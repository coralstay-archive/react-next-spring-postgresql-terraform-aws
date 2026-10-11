package com.example.board.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TASK-7 테스트용 시계: 실제 시각 + 조절 가능한 오프셋. 만료·레이트리밋을 sleep 없이 확인한다. 컨텍스트를 모든 통합 테스트가
 * 같이 쓰므로, 시계를 움직인 테스트는 끝날 때 {@link #reset()} 한다.
 */
public final class MutableClock extends Clock {

	private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

	private final Clock base = Clock.systemUTC();

	public void advance(Duration duration) {
		offset.updateAndGet(current -> current.plus(duration));
	}

	public void reset() {
		offset.set(Duration.ZERO);
	}

	@Override
	public Instant instant() {
		return base.instant().plus(offset.get());
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

}
