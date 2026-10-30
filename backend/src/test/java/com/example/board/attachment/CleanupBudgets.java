package com.example.board.attachment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** TASK-26#4 정리 실행당 예산(건수·시간) 테스트 도우미. */
final class CleanupBudgets {

	private CleanupBudgets() {
	}

	/** {@code base} 에서 정리 예산만 바꾼 설정. */
	static UploadProperties withBudget(UploadProperties base, int maxItems, Duration maxDuration) {
		return new UploadProperties(base.bucket(), base.region(), base.endpoint(), base.pathStyle(), base.accessKey(),
				base.secretKey(), base.publicBasePath(), base.presignTtl(), base.pendingTtl(), base.apiCallTimeout(),
				base.apiCallAttemptTimeout(), base.orphanGracePeriod(), maxItems, maxDuration);
	}

	/** 지금부터 시작해 읽을 때마다 {@code step} 씩 가는 시계(첫 읽기는 시작 시각). */
	static Clock stepping(Duration step) {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
		return new Clock() {

			@Override
			public Instant instant() {
				return now.getAndUpdate(t -> t.plus(step));
			}

			@Override
			public ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				return this;
			}

		};
	}

}
