package com.example.board.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;

import com.example.board.auth.AuthUser;
import com.example.board.auth.AuthUserAuthentication;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

/**
 * 자체 서명(HS256) access 토큰 발급·검증 (decision-5). 보안 체인의 Resource Server 가 {@link #decode} 와
 * {@link #toAuthentication} 을 쓰고, 로그인 API(TASK-3)는 {@link #issueAccessToken} 을 쓴다.
 *
 * <p>클레임: {@code iss}, {@code sub}(user id), {@code iat}, {@code exp}, {@code jti}, {@code role}, {@code status},
 * {@code token_type=access}, {@code step_up_required}(TASK-7 B4). refresh 토큰은 JWT 가 아니라 DB(refresh_tokens)에 해시로 둔다.
 */
@Service
public class JwtTokenService {

	private static final Log log = LogFactory.getLog(JwtTokenService.class);

	public static final String CLAIM_ROLE = "role";

	public static final String CLAIM_STATUS = "status";

	public static final String CLAIM_TOKEN_TYPE = "token_type";

	/** 제한 토큰 표시 (TASK-7 B4). true 면 step-up 전까지 쓰기 403. 없으면 false 로 본다. */
	public static final String CLAIM_STEP_UP_REQUIRED = "step_up_required";

	public static final String ACCESS = "access";

	private final JwtProperties properties;

	private final Clock clock;

	private final NimbusJwtEncoder encoder;

	private final NimbusJwtDecoder decoder;

	/** 만료 판정만 뺀 검증기(서명·발급자·sub·토큰 종류는 그대로). 만료만 된 토큰을 가려낼 때만 쓴다 (TASK-24#1). */
	private final NimbusJwtDecoder ignoringExpiry;

	/** 발급(iat·exp)과 검증(만료 판정)이 같은 Clock 빈을 쓴다 (TASK-23#6). */
	public JwtTokenService(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		if (properties.secret().startsWith("dev-only-")) {
			log.warn("app.jwt.secret 이 개발용 기본값이다. 운영에서는 env JWT_SECRET 으로 반드시 바꾼다.");
		}
		SecretKey key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.encoder = NimbusJwtEncoder.withSecretKey(key).build();
		this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		// Spring Security 기본 검증기(typ=JWT·x5t 지문·만료)는 그대로 두고, 만료 판정만 Clock 빈을 쓰는 것으로 바꾼다
		this.decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
				new JwtIssuerValidator(properties.issuer()), timestampValidator(clock),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
				new JwtClaimValidator<String>(CLAIM_TOKEN_TYPE, ACCESS::equals)));
		this.ignoringExpiry = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		this.ignoringExpiry.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtTypeValidator.jwt(),
				new JwtIssuerValidator(properties.issuer()),
				new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
				new JwtClaimValidator<String>(CLAIM_TOKEN_TYPE, ACCESS::equals)));
	}

	private static JwtTimestampValidator timestampValidator(Clock clock) {
		JwtTimestampValidator validator = new JwtTimestampValidator();
		validator.setClock(clock);
		return validator;
	}

	public String issueAccessToken(AuthUser user) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())
			.subject(String.valueOf(user.id()))
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl()))
			.id(UUID.randomUUID().toString())
			.claim(CLAIM_ROLE, user.role().name())
			.claim(CLAIM_STATUS, user.status().name())
			.claim(CLAIM_TOKEN_TYPE, ACCESS)
			.claim(CLAIM_STEP_UP_REQUIRED, user.stepUpRequired())
			.build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	/** 서명·만료·발급자·토큰 종류를 검증한다. 실패하면 {@link JwtException}. */
	public Jwt decode(String token) {
		return decoder.decode(token);
	}

	/**
	 * 만료 말고는 멀쩡한 토큰(서명·발급자·sub·토큰 종류가 맞고 만료 시각만 지났다)이면 그 토큰을, 아니면 empty (TASK-24#1, BE-15).
	 * 정상 토큰·위조·형식 오류는 empty 다.
	 */
	public Optional<Jwt> expiredOnly(String token) {
		try {
			decoder.decode(token);
			return Optional.empty();
		}
		catch (JwtException ex) {
			// 아래에서 만료만 뺀 검증으로 다시 본다
		}
		try {
			Jwt jwt = ignoringExpiry.decode(token);
			Instant expiresAt = jwt.getExpiresAt();
			return expiresAt != null && expiresAt.isBefore(clock.instant()) ? Optional.of(jwt) : Optional.empty();
		}
		catch (JwtException ex) {
			return Optional.empty();
		}
	}

	public AuthUser toAuthUser(Jwt jwt) {
		try {
			return new AuthUser(Long.valueOf(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
					UserStatus.valueOf(jwt.getClaimAsString(CLAIM_STATUS)),
					Boolean.TRUE.equals(jwt.getClaimAsBoolean(CLAIM_STEP_UP_REQUIRED)));
		}
		catch (RuntimeException ex) {
			throw new InvalidBearerTokenException("access 토큰 클레임이 올바르지 않다", ex);
		}
	}

	public AuthUserAuthentication toAuthentication(Jwt jwt) {
		return AuthUserAuthentication.of(toAuthUser(jwt));
	}

	public JwtProperties properties() {
		return properties;
	}

}
