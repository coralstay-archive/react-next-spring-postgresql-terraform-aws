package com.example.board.common;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 앱의 유일한 시계 (TASK-23#6, BE-7). 만료·한도 판정, 엔티티 생성·수정 시각({@link ClockEntityListener}), 글 시각, JWT 발급·검증, 연결
 * 플래그 만료가 모두 이 빈을 쓴다. 시스템 시계를 직접 읽는 곳은 여기뿐이다(ClockUsageTest 가 검사). 테스트는 조절 가능한 시계로 바꿔
 * sleep 없이 확인한다.
 */
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
