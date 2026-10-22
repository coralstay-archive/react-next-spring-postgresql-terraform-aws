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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthErrors;
import com.example.board.auth.AuthUser;
import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * JWT 클라이언트용 자격 발급: 짧은 access(JWT) + 긴 refresh(불투명 랜덤 값, DB 에 SHA-256 해시) (decision-5).
 */
@Service
public class TokenService {

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final JwtTokenService jwt;

	private final JwtProperties properties;

	private static final Log log = LogFactory.getLog(TokenService.class);

	private final RefreshTokenRepository refreshTokens;

	private final UserRepository users;

	private final SecureRandom random = new SecureRandom();

	private final Clock clock;

	public TokenService(JwtTokenService jwt, RefreshTokenRepository refreshTokens, UserRepository users, Clock clock) {
		this.clock = clock;
		this.jwt = jwt;
		this.properties = jwt.properties();
		this.refreshTokens = refreshTokens;
		this.users = users;
	}

	/** 로그인: 새 family 를 시작한다. */
	@Transactional
	public TokenResponse issueForLogin(User user) {
		return issueForLogin(user, false);
	}

	/**
	 * 로그인: 새 family 를 시작한다. {@code stepUpRequired} 면 제한 토큰(TASK-7 B4)이고, 이 family 를 로테이션해 받는 토큰도 계속
	 * 제한이다.
	 *
	 * <p>정지와의 경쟁(TASK-24#3, BE-17): 같은 트랜잭션에서 사용자 행을 FOR SHARE 로 잠그고 status 를 다시 본다. 정지 트랜잭션은 같은 행을
	 * FOR UPDATE 로 잡으므로 둘이 줄을 선다 — 정지가 먼저면 여기서 SUSPENDED 를 보고 403 {@code suspended}(롤백, refresh 행이 남지
	 * 않는다), 로그인이 먼저면 정지의 "refresh 전부 폐기"가 이 커밋 뒤에 실행돼 새 refresh 도 폐기한다.
	 */
	@Transactional
	public TokenResponse issueForLogin(User user, boolean stepUpRequired) {
		if (!users.lockStatusForShare(user.getId()).map(UserStatus.ACTIVE.name()::equals).orElse(false)) {
			throw AuthErrors.suspendedLogin();
		}
		return respond(user, store(user.getId(), UUID.randomUUID(), stepUpRequired).raw(), stepUpRequired);
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

	/**
	 * refresh 로테이션 (AC#5). 결과를 예외 대신 값으로 돌려주는 이유: 재사용 감지·정지 시의 family 폐기는 401/403 응답과
	 * 함께 <em>커밋돼야</em> 하므로 트랜잭션을 롤백시키는 예외를 쓰지 않는다.
	 *
	 * <ol>
	 * <li>토큰 행을 {@code SELECT ... FOR UPDATE} 로 잠근다. 같은 토큰의 동시 요청은 여기서 줄을 선다.</li>
	 * <li>이미 로테이션된 토큰(replaced_by 있음)이면 재사용이다: family 의 살아 있는 토큰을 모두 폐기하고 INVALID (B3).
	 * 동시 요청에서 늦게 온 쪽도 앞 요청의 커밋 뒤 이 상태를 보게 되므로 똑같이 처리된다(유예 기간 없음 — 엄격한 쪽을 택했다).</li>
	 * <li>그 밖에 폐기·만료·모르는 토큰, 계정이 없으면 INVALID.</li>
	 * <li>계정이 ACTIVE 가 아니면 family 를 폐기하고 SUSPENDED (로그인과 같은 403). 정지가 풀리면 다시 로그인한다.</li>
	 * <li>같은 family 로 새 토큰을 저장하고, 이전 토큰에 replaced_by·revoked_at 을 기록한다. access 토큰은 DB 의 최신 role/status 로 만든다.</li>
	 * </ol>
	 */
	@Transactional
	public Rotation rotate(String rawRefreshToken) {
		Instant now = now();
		RefreshToken token = refreshTokens.findForUpdateByTokenHash(hash(rawRefreshToken)).orElse(null);
		if (token == null) {
			return Rotation.INVALID;
		}
		if (token.isRotated()) {
			int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
			log.warn("refresh 토큰 재사용 감지: user=" + token.getUserId() + " family=" + token.getFamilyId()
					+ " 폐기=" + revoked);
			return Rotation.INVALID;
		}
		if (token.isRevoked() || token.isExpired(now)) {
			return Rotation.INVALID;
		}
		User user = users.findById(token.getUserId()).orElse(null);
		if (user == null) {
			return Rotation.INVALID;
		}
		if (user.getStatus() != UserStatus.ACTIVE) {
			refreshTokens.revokeFamily(token.getFamilyId(), now);
			return Rotation.SUSPENDED;
		}
		Issued next = store(user.getId(), token.getFamilyId(), token.isStepUpRequired());
		token.rotatedTo(next.token(), now);
		return new Rotation(Rotation.Outcome.ROTATED, respond(user, next.raw(), token.isStepUpRequired()));
	}

	/** {@link #rotate} 결과. ROTATED 일 때만 tokens 가 있다. */
	public record Rotation(Outcome outcome, TokenResponse tokens) {

		static final Rotation INVALID = new Rotation(Outcome.INVALID, null);

		static final Rotation SUSPENDED = new Rotation(Outcome.SUSPENDED, null);

		public enum Outcome {
			ROTATED, INVALID, SUSPENDED
		}

	}

	/** 새 refresh 토큰을 만들어 해시만 저장하고, 원문과 저장된 엔티티를 돌려준다. */
	Issued store(Long userId, UUID familyId, boolean stepUpRequired) {
		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant now = clock.instant();
		RefreshToken saved = refreshTokens
			.saveAndFlush(RefreshToken.issue(userId, hash(raw), familyId, now.plus(properties.refreshTokenTtl()),
					stepUpRequired));
		return new Issued(raw, saved);
	}

	TokenResponse respond(User user, String rawRefreshToken, boolean stepUpRequired) {
		return TokenResponse.bearer(jwt.issueAccessToken(AuthUser.from(user, stepUpRequired)), rawRefreshToken,
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
