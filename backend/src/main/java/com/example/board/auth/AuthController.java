package com.example.board.auth;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.local.LocalAccountService;
import com.example.board.auth.local.SignupRequest;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;

/**
 * 인증 API ({@code /api/auth/*}). 같은 엔드포인트가 세션(쿠키 + CSRF)과 JWT(Bearer) 클라이언트를 모두 받는다 (decision-5).
 */
@RestController
public class AuthController {

	private static final String IDENTITY_UNIQUE = "user_identities_provider_subject_uk";

	private final LocalAccountService localAccounts;

	public AuthController(LocalAccountService localAccounts) {
		this.localAccounts = localAccounts;
	}

	/**
	 * LOCAL 가입. 자동 로그인하지 않는다(세션·토큰을 만들지 않음). 공개 엔드포인트지만 세션 경로라 CSRF 는 검사한다.
	 */
	@PostMapping("/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public UserSummary signup(@Valid @RequestBody SignupRequest request) {
		try {
			User user = localAccounts.signup(request);
			return UserSummary.of(user, List.of(AuthProvider.LOCAL));
		}
		catch (DataIntegrityViolationException ex) {
			// 동시 가입 경쟁에서 진 쪽 (B1): UNIQUE(provider, subject) 위반만 409 로 바꾼다.
			if (String.valueOf(ex.getMostSpecificCause().getMessage()).contains(IDENTITY_UNIQUE)) {
				throw LocalAccountService.emailTaken();
			}
			throw ex;
		}
	}

}
