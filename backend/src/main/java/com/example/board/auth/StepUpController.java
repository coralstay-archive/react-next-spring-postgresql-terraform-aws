package com.example.board.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.local.LocalAccountService;
import com.example.board.auth.token.TokenService;
import com.example.board.common.ApiException;
import com.example.board.security.BearerRequests;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * 재활용 번호 보호의 step-up (TASK-7 B4). 휴대폰으로 로그인한 제한 세션·토큰을, 같은 계정의 기존 수단으로 다시 인증해 제한 없는
 * 세션·토큰으로 바꾼다.
 *
 * <ul>
 * <li>LOCAL: {@code POST /api/auth/step-up/local {password}} (여기).</li>
 * <li>소셜·Cognito: 같은 계정의 identity 로 OAuth 로그인({@code /api/oauth2/authorization/{p}})을 다시 하면 된다.
 * IdentityResolver 가 같은 계정을 찾고 {@link SessionLogin} 이 제한 없는 새 세션을 만든다(별도 엔드포인트 없음).</li>
 * </ul>
 * 인증 필요(보안 체인 기본 규칙). 세션은 CSRF 검사 대상이고, 제한 쓰기 필터·정지 필터 순서상 정지 계정은 403 suspended 다.
 */
@RestController
public class StepUpController {

	public static final String STEP_UP_UNAVAILABLE = "step_up_unavailable";

	private final LocalAccountService localAccounts;

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final SessionLogin sessionLogin;

	private final TokenService tokens;

	public StepUpController(LocalAccountService localAccounts, UserRepository users,
			UserIdentityRepository identities, SessionLogin sessionLogin, TokenService tokens) {
		this.localAccounts = localAccounts;
		this.users = users;
		this.identities = identities;
		this.sessionLogin = sessionLogin;
		this.tokens = tokens;
	}

	/**
	 * 비밀번호가 맞으면: 세션은 세션 ID 를 바꾼 제한 없는 세션(응답은 사용자 요약), Bearer 는 제한 없는 새 access+refresh(본문의
	 * refreshToken family 는 폐기). 틀리면 401 invalid_credentials, LOCAL 이 없는 계정은 400 step_up_unavailable.
	 */
	@PostMapping("/auth/step-up/local")
	public Object local(@Valid @RequestBody StepUpLocalRequest body, @AuthenticationPrincipal AuthUser principal,
			HttpServletRequest request, HttpServletResponse response) {
		if (!localAccounts.verifyPassword(principal.id(), body.password())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, STEP_UP_UNAVAILABLE,
					"이 계정에는 비밀번호 로그인 수단이 없습니다. 연결된 다른 수단으로 다시 로그인하세요");
		}
		User user = users.findById(principal.id())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "인증이 필요합니다"));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "suspended", "정지된 계정은 로그인할 수 없습니다");
		}
		if (BearerRequests.isBearer(request)) {
			if (body.refreshToken() != null && !body.refreshToken().isBlank()) {
				tokens.revokeFamilyOf(user.getId(), body.refreshToken());
			}
			return tokens.issueForLogin(user, false);
		}
		sessionLogin.login(user, false, request, response);
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()), false);
	}

}
