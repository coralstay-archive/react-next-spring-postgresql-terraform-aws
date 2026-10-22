package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

import com.example.board.auth.local.LocalAccountService;
import com.example.board.common.ApiException;
import com.example.board.common.RateLimitProperties;
import com.example.board.common.RateLimiter;
import com.example.board.common.RateLimiter.Reservation;
import com.example.board.common.RateLimiter.Rule;

/**
 * TASK-22#1 (리뷰 L2) 실패만 세는 쪽에서 예약 해제({@link RateLimiter#release})가 실패해도, 성공한 시도를 500 으로 바꾸거나 원래 예외를
 * 가리지 않는다. 해제 실패는 WARN 으로만 남기고(이메일·IP 없이) 그 시도는 실패로 남는다(한도 쪽으로 안전).
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthRateLimitsTest {

	private static final String EMAIL = "victim@example.com";

	private static final String IP = "203.0.113.7";

	RateLimiter limiter;

	AuthRateLimits rateLimits;

	@BeforeEach
	void setUp() {
		limiter = mock(RateLimiter.class);
		when(limiter.reserve(any(Rule[].class))).thenReturn(new Reservation(List.of(1L, 2L)));
		doThrow(new DataAccessResourceFailureException("connection lost")).when(limiter).release(any());
		rateLimits = new AuthRateLimits(limiter,
				new RateLimitProperties(null, null, null, null, null, null, null, null, null));
	}

	@Test
	@DisplayName("TASK-22#1 성공한 로그인은 해제 실패가 있어도 그대로 성공하고, WARN 에 이메일·IP 를 남기지 않는다")
	void releaseFailureDoesNotFailSuccessfulAttempt(CapturedOutput output) {
		String result = rateLimits.login(EMAIL, IP, () -> "ok");

		assertThat(result).isEqualTo("ok");
		verify(limiter).release(any());
		assertThat(output).contains("WARN").doesNotContain(EMAIL).doesNotContain(IP);
	}

	@Test
	@DisplayName("TASK-22#1 invalid_credentials 가 아닌 오류(예: 403 suspended)는 해제 실패가 있어도 원래 예외가 그대로 나간다")
	void releaseFailureDoesNotMaskOriginalException() {
		ApiException suspended = new ApiException(HttpStatus.FORBIDDEN, "suspended", "정지된 계정입니다");

		Throwable thrown = catchThrowable(() -> rateLimits.stepUp(42L, () -> {
			throw suspended;
		}));

		assertThat(thrown).isSameAs(suspended);
	}

	@Test
	@DisplayName("TASK-22#1 invalid_credentials 는 실패로 남기므로 해제하지 않는다")
	void invalidCredentialsKeepsReservation() {
		ApiException invalid = new ApiException(HttpStatus.UNAUTHORIZED, LocalAccountService.INVALID_CREDENTIALS, "x");

		assertThat(catchThrowable(() -> rateLimits.login(EMAIL, IP, () -> {
			throw invalid;
		}))).isSameAs(invalid);
		verify(limiter, never()).release(any());
	}

}
