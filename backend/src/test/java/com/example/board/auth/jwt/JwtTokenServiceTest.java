package com.example.board.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.board.auth.AuthUser;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

/** TASK-2#2 자체 서명 JWT access 토큰 발급·검증. */
class JwtTokenServiceTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";

	JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, "board-test", Duration.ofMinutes(5), Duration.ofDays(14)), Clock.systemUTC());

	@Test
	@DisplayName("TASK-2#2 발급한 access 토큰을 검증하면 같은 AuthUser 가 나온다")
	void roundTrip() {
		AuthUser user = new AuthUser(42L, Role.ADMIN, UserStatus.ACTIVE);

		Jwt jwt = service.decode(service.issueAccessToken(user));

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("board-test");
		assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
		assertThat(service.toAuthUser(jwt)).isEqualTo(user);
	}

	@Test
	@DisplayName("TASK-2#2 다른 발급자(iss) 토큰은 거부한다")
	void rejectsOtherIssuer() {
		JwtTokenService other = new JwtTokenService(new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(5), Duration.ofDays(14)), Clock.systemUTC());
		String token = other.issueAccessToken(new AuthUser(1L, Role.USER, UserStatus.ACTIVE));

		assertThatThrownBy(() -> service.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("TASK-2#2 HMAC 키가 32바이트보다 짧으면 시작할 수 없다")
	void rejectsShortSecret() {
		assertThatThrownBy(() -> new JwtProperties("too-short", "board", Duration.ofMinutes(5), Duration.ofDays(14)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("app.jwt.secret");
	}

	@Test
	@DisplayName("TASK-23#6 만료 판정을 Clock 빈으로 바꿔도 Spring Security 기본 검증기는 그대로: typ 헤더가 JWT 가 아닌 토큰(서명·클레임은 정상)은 거부한다")
	void rejectsNonJwtType() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("board-test")
			.subject("1")
			.issuedAt(now)
			.expiresAt(now.plusSeconds(60))
			.claim(JwtTokenService.CLAIM_ROLE, "USER")
			.claim(JwtTokenService.CLAIM_STATUS, "ACTIVE")
			.claim(JwtTokenService.CLAIM_TOKEN_TYPE, JwtTokenService.ACCESS)
			.build();
		NimbusJwtEncoder encoder = NimbusJwtEncoder
			.withSecretKey(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
			.build();
		String typed = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
			.getTokenValue();
		String dpop = encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("dpop+jwt").build(), claims))
			.getTokenValue();

		assertThat(service.decode(typed).getSubject()).isEqualTo("1");
		assertThatThrownBy(() -> service.decode(dpop)).isInstanceOf(JwtException.class);
	}

}
