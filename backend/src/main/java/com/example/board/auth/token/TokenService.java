package com.example.board.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.user.User;

/**
 * JWT 클라이언트용 자격 발급: 짧은 access(JWT) + 긴 refresh(불투명 랜덤 값, DB 에 SHA-256 해시) (decision-5).
 */
@Service
public class TokenService {

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final JwtTokenService jwt;

	private final JwtProperties properties;

	private final RefreshTokenRepository refreshTokens;

	private final SecureRandom random = new SecureRandom();

	private final Clock clock = Clock.systemUTC();

	public TokenService(JwtTokenService jwt, RefreshTokenRepository refreshTokens) {
		this.jwt = jwt;
		this.properties = jwt.properties();
		this.refreshTokens = refreshTokens;
	}

	/** 로그인: 새 family 를 시작한다. */
	@Transactional
	public TokenResponse issueForLogin(User user) {
		return issue(user, UUID.randomUUID());
	}

	/**
	 * 로그아웃: 이 사용자의 refresh 토큰이면 그 family 전체를 폐기한다. 모르는 토큰이나 남의 토큰은 조용히 무시한다
	 * (로그아웃은 항상 성공 응답이고, 남의 세션을 끊는 수단이 되면 안 된다).
	 */
	@Transactional
	public void revokeFamilyOf(Long userId, String rawRefreshToken) {
		refreshTokens.findByTokenHash(hash(rawRefreshToken))
			.filter(token -> token.getUserId().equals(userId))
			.ifPresent(token -> refreshTokens.revokeFamily(token.getFamilyId(), now()));
	}

	TokenResponse issue(User user, UUID familyId) {
		return respond(user, store(user.getId(), familyId).raw());
	}

	/** 새 refresh 토큰을 만들어 해시만 저장하고, 원문과 저장된 엔티티를 돌려준다. */
	Issued store(Long userId, UUID familyId) {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		RefreshToken saved = refreshTokens
			.saveAndFlush(RefreshToken.issue(userId, hash(raw), familyId, now.plus(properties.refreshTokenTtl())));
		return new Issued(raw, saved);
	}

	TokenResponse respond(User user, String rawRefreshToken) {
		return TokenResponse.bearer(jwt.issueAccessToken(AuthUser.from(user)), rawRefreshToken,
				properties.accessTokenTtl().toSeconds());
	}

	Instant now() {
		return clock.instant();
	}

	/** refresh 토큰 원문 → 저장용 SHA-256 hex. 원문이 256비트 랜덤이라 솔트·느린 해시가 필요 없다. */
	public static String hash(String raw) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	record Issued(String raw, RefreshToken token) {
	}

}
