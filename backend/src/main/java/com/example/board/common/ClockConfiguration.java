package com.example.board.common;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각이 필요한 규칙(OTP 만료·발송 한도)이 쓰는 시계. 테스트는 조절 가능한 시계로 바꿔 sleep 없이 확인한다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	private static final Duration MICROSECOND = Duration.ofNanos(1_000);

	@Bean
	Clock clock() {
		return microsecondClock(Clock.systemUTC());
	}

	/**
	 * TASK-19: PostgreSQL timestamptz 는 마이크로초까지 저장한다. Linux 의 시스템 시계는 나노초라, 그대로 쓰면 저장 직후 응답(메모리 값)과
	 * 다시 읽은 값이 달라진다. 앱 시계를 마이크로초로 절삭해 둘을 같게 한다.
	 */
	public static Clock microsecondClock(Clock base) {
		return Clock.tick(base, MICROSECOND);
	}

}
