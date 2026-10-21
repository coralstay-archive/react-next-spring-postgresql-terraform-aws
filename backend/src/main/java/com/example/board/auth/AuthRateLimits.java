package com.example.board.auth;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <li>로그인: 실패를 (계정(정규화 이메일), 클라이언트 IP) 조합별({@value #LOGIN_ACCOUNT})과 IP 별({@value #LOGIN_IP})로 센다.
 * 계정 한도를 IP 와 묶은 것은 다른 IP 의 공격자가 피해자 계정을 잠그지 못하게 하려는 것이다(잠금 DoS). 여러 IP 로 나눠 한 계정을
 * 추측하는 것은 IP 별 한도가 막는다. 한도에 이르면 비밀번호를 검사하지 않고(BCrypt CPU 보호) 429. 키가 입력 이메일이라 계정이 있든
 * 없든 똑같이 세고 똑같이 막는다(B2).</li>
 * <li>가입: 시도를 IP 별로 센다(성공·409 모두 — 이메일 존재 여부 탐색도 막는다).</li>
 * <li>step-up: 실패를 사용자별로 센다(B1). 제한 세션·토큰을 바꿔 가며 추측해도 같은 사용자다.</li>
 * </ul>
 * 실패만 세는 쪽은 {@link RateLimiter#reserve} 로 미리 자리를 잡고, 결과가 {@code invalid_credentials} 가 아니면 지운다. 지우기가
 * 실패해도(DB 순간 장애 등) 성공한 시도를 500 으로 바꾸거나 원래 예외를 가리지 않는다 — WARN 만 남기고(이메일·IP 없이) 그 시도는 실패로
 * 남는다(한도 쪽으로 안전한 방향).
 */
@Component
public class AuthRateLimits {

	private static final Logger log = LoggerFactory.getLogger(AuthRateLimits.class);

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
		return countingFailures(attempt, new Rule(LOGIN_ACCOUNT, Emails.normalize(email) + "\n" + clientIp, properties.loginAccount()),
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
				release(reservation);
			}
		}
	}

	private void release(Reservation reservation) {
		try {
			limiter.release(reservation);
		}
		catch (RuntimeException ex) {
			// 키(이메일·IP)는 남기지 않는다. 예약 이벤트 id 만으로 충분하다
			log.warn("레이트리밋 예약 해제 실패 — 이 시도가 창이 지날 때까지 실패로 남는다 (events={})", reservation.ids(), ex);
		}
	}

}
