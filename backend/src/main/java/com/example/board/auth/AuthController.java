package com.example.board.auth;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.auth.local.LocalAccountService;
import com.example.board.auth.local.LoginRequest;
import com.example.board.auth.local.SignupRequest;
import com.example.board.auth.token.RefreshRequest;
import com.example.board.auth.token.TokenResponse;
import com.example.board.auth.token.TokenService;
import com.example.board.common.ApiErrors;
import com.example.board.common.ApiException;
import com.example.board.security.BearerRequests;
import com.example.board.security.ClientIpResolver;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * 인증 API ({@code /api/auth/*}). 같은 엔드포인트가 세션(쿠키 + CSRF)과 JWT(Bearer) 클라이언트를 모두 받는다 (decision-5).
 */
@RestController
public class AuthController {

	/** {@code POST /api/auth/login?token=jwt} 의 파라미터 값. */
	public static final String TOKEN_MODE_JWT = "jwt";

	private static final String IDENTITY_UNIQUE = "user_identities_provider_subject_uk";

	private final LocalAccountService localAccounts;

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final SessionLogin sessionLogin;

	private final TokenService tokens;

	private final AuthRateLimits rateLimits;

	private final ClientIpResolver clientIps;

	private final JwtTokenService jwt;

	private static final BearerTokenResolver BEARER_TOKENS = new DefaultBearerTokenResolver();

	public AuthController(LocalAccountService localAccounts, UserRepository users, UserIdentityRepository identities,
			SessionLogin sessionLogin, TokenService tokens, AuthRateLimits rateLimits, ClientIpResolver clientIps,
			JwtTokenService jwt) {
		this.jwt = jwt;
		this.localAccounts = localAccounts;
		this.users = users;
		this.identities = identities;
		this.sessionLogin = sessionLogin;
		this.tokens = tokens;
		this.rateLimits = rateLimits;
		this.clientIps = clientIps;
	}

	/**
	 * LOCAL 가입. 자동 로그인하지 않는다(세션·토큰을 만들지 않음). 공개 엔드포인트지만 세션 경로라 CSRF 는 검사한다. 클라이언트 IP 별
	 * 시도 한도를 넘으면 429 (TASK-22#1).
	 */
	@PostMapping("/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserSummary signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		rateLimits.signup(clientIps.resolve(http));
		try {
			User user = localAccounts.signup(request);
			return summary(user);
		}
		catch (DataIntegrityViolationException ex) {
			// 동시 가입 경쟁에서 진 쪽 (B1): UNIQUE(provider, subject) 위반만 409 로 바꾼다.
			if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains(IDENTITY_UNIQUE)) {
				throw LocalAccountService.emailTaken();
			}
			throw ex;
		}
	}

	/**
	 * LOCAL 로그인.
	 *
	 * <ul>
	 * <li>기본: 웹 세션을 만든다({@link SessionLogin}). 응답은 사용자 요약. CSRF 검사 대상이다(로그인 CSRF 방어).</li>
	 * <li>{@code ?token=jwt}: 세션 없이 access+refresh 를 본문으로 돌려준다. 비브라우저 흐름이라 CSRF 면제
	 * (이유는 {@code SecurityConfig#csrfExempt} 참고).</li>
	 * </ul>
	 * 실패는 모두 같은 401 (B2). 비밀번호가 맞은 정지 계정은 403. 실패가 계정별·IP 별 한도에 이르면 비밀번호를 검사하지 않고 429
	 * (TASK-22#1, 계정이 없어도 같은 응답).
	 */
	@PostMapping("/auth/login")
	public Object login(@Valid @RequestBody LoginRequest body, @RequestParam(name = "token", required = false) String token,
			HttpServletRequest request, HttpServletResponse response) {
		if (token != null && !TOKEN_MODE_JWT.equals(token)) {
			throw unsupportedTokenMode();
		}
		User user = rateLimits.login(body.email(), clientIps.resolve(request),
				() -> localAccounts.authenticate(body.email(), body.password()));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw suspended();
		}
		if (token != null) {
			return tokens.issueForLogin(user);
		}
		if (BearerRequests.isBearer(request)) {
			// Bearer 요청은 세션을 쓰지 않으므로(decision-5) 세션 로그인을 할 수 없다.
			throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "Bearer 요청은 ?token=jwt 로 로그인하세요");
		}
		sessionLogin.login(user, request, response);
		return summary(user);
	}

	/**
	 * 로그아웃. 항상 204 (로그인하지 않은 상태여도 멱등).
	 *
	 * <ul>
	 * <li>세션: 세션 무효화 + SESSION 쿠키 만료 + XSRF-TOKEN 삭제. CSRF 검사 대상이다(강제 로그아웃 방지).</li>
	 * <li>Bearer: 본문 {@code {refreshToken}} 이 있으면 그 family 를 폐기한다. access 토큰은 상태가 없어 만료(최대 15분)까지
	 * 유효하다 — 클라이언트가 버린다. access 토큰이 이미 만료됐어도(보안 체인이 401 대신 익명으로 넘긴다) 그 토큰의 서명 검증된 사용자
	 * 것이면 폐기한다(TASK-24 B1). 남의 refresh 토큰은 어느 쪽이든 폐기하지 않는다.</li>
	 * </ul>
	 * 정지된 사용자도 로그아웃은 해야 하므로 {@code SuspendedUserWriteFilter} 의 예외 경로다.
	 */
	@PostMapping("/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@RequestBody(required = false) RefreshRequest body, @AuthenticationPrincipal AuthUser user,
			HttpServletRequest request, HttpServletResponse response) {
		if (BearerRequests.isBearer(request)) {
			Long userId = user != null ? user.id() : expiredBearerUserId(request);
			if (userId != null && body != null && body.refreshToken() != null && !body.refreshToken().isBlank()) {
				tokens.revokeFamilyOf(userId, body.refreshToken());
			}
			return;
		}
		sessionLogin.logout(request, response);
	}

	/** 보안 체인이 만료돼서 무시한 Bearer 토큰(TASK-24#1)의 사용자 id. 없거나 만료가 아닌 다른 이유로 틀리면 null. */
	private Long expiredBearerUserId(HttpServletRequest request) {
		String token = BEARER_TOKENS.resolve(request);
		if (token == null) {
			return null;
		}
		return jwt.expiredOnly(token).map(expired -> jwt.toAuthUser(expired).id()).orElse(null);
	}

	/**
	 * refresh 로테이션 (AC#5). Bearer 헤더는 필요 없다. 만료된 access 토큰을 같이 보내도 무시한다(TASK-24#1, 위조 토큰은 401).
	 * CSRF 면제 이유는 {@code SecurityConfig#csrfExempt}. 실패는 401 {@code invalid_refresh_token}, 정지 계정은 403.
	 */
	@PostMapping("/auth/token/refresh")
	public TokenResponse refresh(@Valid @RequestBody RefreshRequest body) {
		TokenService.Rotation rotation = tokens.rotate(body.refreshToken());
		return switch (rotation.outcome()) {
			case ROTATED -> rotation.tokens();
			case SUSPENDED -> throw suspended();
			case INVALID -> throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token",
					"refresh 토큰이 유효하지 않습니다. 다시 로그인하세요");
		};
	}

	/**
	 * 현재 사용자. 세션·Bearer 공통. 세션·토큰에 실린 role/status 는 발급 시점 값이라, 응답은 DB 의 최신 값으로 만든다.
	 * 계정이 지워졌으면 401. 익명은 보안 체인이 401 로 막는다. {@code stepUpRequired}·{@code stepUpProviders} 는 이 세션·토큰의
	 * 제한 여부와 다시 인증할 수 있는 수단이다(TASK-7 B4).
	 */
	@GetMapping("/auth/me")
	public UserSummary me(@AuthenticationPrincipal AuthUser principal) {
		User user = users.findById(principal.id())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "인증이 필요합니다"));
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()), principal.stepUpRequired());
	}

	private static ApiException suspended() {
		return new ApiException(HttpStatus.FORBIDDEN, "suspended", "정지된 계정은 로그인할 수 없습니다");
	}

	private UserSummary summary(User user) {
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()));
	}

	/** {@code ?token=} 이 {@code jwt} 가 아님: 파라미터 오류는 400 validation + fields (TASK-20#4). */
	public static ApiException unsupportedTokenMode() {
		String message = "token 파라미터는 jwt 만 지원합니다";
		return new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, message, Map.of("token", message));
	}

}
