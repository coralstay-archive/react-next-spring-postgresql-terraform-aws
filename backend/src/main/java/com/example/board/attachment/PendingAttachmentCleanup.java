package com.example.board.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미확정 첨부 정리 (TASK-11#4). {@code pending-ttl}(기본 24시간)보다 오래된 PENDING 행을 지운다. 객체 삭제는 best-effort 다 — 실패해도
 * S3 lifecycle(status=pending 태그 1일 만료)이 지운다. 확정된 첨부는 건드리지 않는다.
 *
 * <p>순서 (TASK-26#2, BE-33): 행을 먼저 조건부로 지우고({@code status = PENDING} 이고 아직 만료, 한 행씩), 실제로 지운 행의 객체만 지운다.
 * 대상을 고른 뒤 confirm 이 먼저 확정한 행은 남고 그 객체도 건드리지 않는다 → CONFIRMED 첨부는 항상 객체가 있다(B1). 반대로 행을 지운 뒤
 * confirm 이 확정하려 하면 0행이라 confirm 쪽이 객체를 지운다.
 */
@Component
public class PendingAttachmentCleanup {

	static final int BATCH = 500;

	private static final Logger log = LoggerFactory.getLogger(PendingAttachmentCleanup.class);

	private final AttachmentRepository attachments;

	private final UploadStorage storage;

	private final UploadProperties properties;

	private final Clock clock;

	public PendingAttachmentCleanup(AttachmentRepository attachments, UploadStorage storage,
			UploadProperties properties, Clock clock) {
		this.attachments = attachments;
		this.storage = storage;
		this.properties = properties;
		this.clock = clock;
	}

	/** 지운 행 수. 한 번에 {@value #BATCH}개씩, 남은 게 없을 때까지. */
	@Scheduled(fixedDelayString = "${app.uploads.cleanup-interval:PT1H}",
			initialDelayString = "${app.uploads.cleanup-interval:PT1H}")
	public int run() {
		Instant cutoff = clock.instant().minus(properties.pendingTtl());
		int removed = 0;
		while (true) {
			List<Attachment> stale = attachments.findPendingCreatedBefore(cutoff, Limit.of(BATCH));
			if (stale.isEmpty()) {
				break;
			}
			for (Attachment attachment : stale) {
				if (attachments.deleteExpiredPending(attachment.getId(), cutoff) == 1) {
					removed++;
					if (storage.available()) {
						deleteQuietly(attachment.getS3Key());
					}
				}
			}
			if (stale.size() < BATCH) {
				break;
			}
		}
		if (removed > 0) {
			log.info("미확정 첨부 {}건 정리", removed);
		}
		return removed;
	}

	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (RuntimeException ex) {
			// S3 오류 원인은 저장소가 이미 남겼다(TASK-26#1)
			log.warn("미확정 첨부 객체 삭제 실패(S3 lifecycle 이 정리한다) key={} causes={}", key, ex.getClass().getSimpleName());
		}
	}

}
