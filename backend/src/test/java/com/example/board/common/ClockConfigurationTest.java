package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.board.support.MutableClock;

class ClockConfigurationTest {

	private static final Instant NANOS = Instant.parse("2026-10-12T05:00:00.123456789Z");

	@Test
	@DisplayName("TASK-19#4 앱 시계는 마이크로초로 절삭한다(PostgreSQL timestamptz 정밀도와 같게 — Linux 나노초 시계에서 응답·DB 값 불일치 방지)")
	void appClockTruncatesToMicros() {
		Clock clock = ClockConfiguration.microsecondClock(Clock.fixed(NANOS, ZoneOffset.UTC));

		assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-12T05:00:00.123456Z"));
		assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	@DisplayName("TASK-19#4 운영 Clock 빈도 마이크로초 단위다")
	void beanClockHasNoSubMicroNanos() {
		Clock clock = new ClockConfiguration().clock();

		for (int i = 0; i < 1_000; i++) {
			assertThat(clock.instant().getNano() % 1_000).isZero();
		}
	}

	@Test
	@DisplayName("TASK-19#4 테스트용 MutableClock 도 마이크로초 단위다(옮겨도 유지)")
	void mutableClockHasNoSubMicroNanos() {
		MutableClock clock = new MutableClock();
		clock.advance(Duration.ofNanos(1));

		for (int i = 0; i < 1_000; i++) {
			assertThat(clock.instant().getNano() % 1_000).isZero();
		}
	}

}
