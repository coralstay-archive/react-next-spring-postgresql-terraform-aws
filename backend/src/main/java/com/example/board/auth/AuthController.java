package com.example.board.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.local.LocalAccountService;
import com.example.board.auth.local.LoginRequest;
import com.example.board.auth.local.SignupRequest;
import com.example.board.auth.token.TokenService;
import com.example.board.common.ApiException;
import com.example.board.security.BearerRequests;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
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

	private final UserIdentityRepository identities;

	private final SessionLogin sessionLogin;

	private final TokenService tokens;

	public AuthController(LocalAccountService localAccounts, UserIdentityRepository identities,
			SessionLogin sessionLogin, TokenService tokens) {
		this.localAccounts = localAccounts;
		this.identities = identities;
		this.sessionLogin = sessionLogin;
		this.tokens = tokens;
	}

	/**
	 * LOCAL 가입. 자동 로그인하지 않는다(세션·토큰을 만들지 않음). 공개 엔드포인트지만 세션 경로라 CSRF 는 검사한다.
	 */
	@PostMapping("/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserSummary signup(@Valid @RequestBody SignupRequest request) {
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
	 * 실패는 모두 같은 401 (B2). 비밀번호가 맞은 정지 계정은 403.
	 */
	@PostMapping("/auth/login")
	public Object login(@Valid @RequestBody LoginRequest body, @RequestParam(name = "token", required = false) String token,
			HttpServletRequest request, HttpServletResponse response) {
		if (token != null && !TOKEN_MODE_JWT.equals(token)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "token 파라미터는 jwt 만 지원합니다");
		}
		User user = localAccounts.authenticate(body.email(), body.password());
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "suspended", "정지된 계정은 로그인할 수 없습니다");
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

	private UserSummary summary(User user) {
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()));
	}

}
