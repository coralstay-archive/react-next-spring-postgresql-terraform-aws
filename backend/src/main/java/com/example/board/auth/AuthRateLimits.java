package com.example.board.auth;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.example.board.auth.local.Emails;
import com.example.board.auth.local.LocalAccountService;
import com.example.board.common.ApiException;
import com.example.board.common.RateLimitProperties;
import com.example.board.common.RateLimiter;
import com.example.board.common.RateLimiter.Reservation;
import com.example.board.common.RateLimiter.Rule;

/**
 * 로그인·가입·step-up 레이트리밋 (TASK-22#1, doc-2 BE-13). 한도는 {@link RateLimitProperties}.
 *
 * <ul>
 * <li>로그인: 실패를 계정(정규화 이메일)별·IP 별로 센다. 한도에 이르면 비밀번호를 검사하지 않고(BCrypt CPU 보호) 429. 키가
 * 입력 이메일이라 계정이 있든 없든 똑같이 세고 똑같이 막는다(B2).</li>
 * <li>가입: 시도를 IP 별로 센다(성공·409 모두 — 이메일 존재 여부 탐색도 막는다).</li>
 * <li>step-up: 실패를 사용자별로 센다(B1). 제한 세션·토큰을 바꿔 가며 추측해도 같은 사용자다.</li>
 * </ul>
 * 실패만 세는 쪽은 {@link RateLimiter#reserve} 로 미리 자리를 잡고, 결과가 {@code invalid_credentials} 가 아니면 지운다.
 */
@Component
public class AuthRateLimits {

	static final String LOGIN_ACCOUNT = "login-account";

	static final String LOGIN_IP = "login-ip";

	static final String SIGNUP_IP = "signup-ip";

	static final String STEP_UP = "step-up";

	private final RateLimiter limiter;

	private final RateLimitProperties properties;

	public AuthRateLimits(RateLimiter limiter, RateLimitProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	public <T> T login(String email, String clientIp, Supplier<T> attempt) {
		return countingFailures(attempt, new Rule(LOGIN_ACCOUNT, Emails.normalize(email), properties.loginAccount()),
				new Rule(LOGIN_IP, clientIp, properties.loginIp()));
	}

	public void signup(String clientIp) {
		limiter.hit(new Rule(SIGNUP_IP, clientIp, properties.signupIp()));
	}

	public <T> T stepUp(Long userId, Supplier<T> attempt) {
		return countingFailures(attempt, new Rule(STEP_UP, String.valueOf(userId), properties.stepUp()));
	}

	private <T> T countingFailures(Supplier<T> attempt, Rule... rules) {
		Reservation reservation = limiter.reserve(rules);
		boolean failed = false;
		try {
			return attempt.get();
		}
		catch (ApiException ex) {
			failed = LocalAccountService.INVALID_CREDENTIALS.equals(ex.getError());
			throw ex;
		}
		finally {
			if (!failed) {
				limiter.release(reservation);
			}
		}
	}

}
