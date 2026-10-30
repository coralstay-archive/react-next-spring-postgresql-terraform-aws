package com.example.board.attachment;

import java.time.Clock;
import java.time.Instant;

/**
 * 정리 작업 한 번 실행의 예산 (TASK-26#4): 최대 건수({@code app.uploads.cleanup-max-items})와 최대 시간
 * ({@code app.uploads.cleanup-max-duration}). 스케줄 락 lockAtMostFor(30분)보다 오래 돌아 다른 인스턴스와 겹치지 않게, 쌓인 일이 많으면
 * 이번 실행은 예산만큼만 하고 나머지는 다음 실행에 맡긴다.
 */
final class CleanupBudget {

	private final Clock clock;

	private final int maxItems;

	private final Instant deadline;

	private int used;

	private boolean exhausted;

	CleanupBudget(Clock clock, Instant start, UploadProperties properties) {
		this.clock = clock;
		this.maxItems = properties.cleanupMaxItems();
		this.deadline = start.plus(properties.cleanupMaxDuration());
	}

	/** 한 건 더 처리해도 되면 쓰고 true. 건수나 시간이 다 됐으면 false(이후로도 계속 false). */
	boolean tryUse() {
		if (exhausted || used >= maxItems || !clock.instant().isBefore(deadline)) {
			exhausted = true;
			return false;
		}
		used++;
		return true;
	}

	boolean exhausted() {
		return exhausted;
	}

}
