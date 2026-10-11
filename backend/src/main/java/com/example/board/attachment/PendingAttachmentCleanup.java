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
 * <p>행 삭제는 {@code status = PENDING} 조건으로만 해서, 그사이 confirm 된 첨부는 남는다.
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
			if (storage.available()) {
				stale.forEach(attachment -> deleteQuietly(attachment.getS3Key()));
			}
			int deleted = attachments.deletePending(stale.stream().map(Attachment::getId).toList());
			removed += deleted;
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
			log.warn("미확정 첨부 객체 삭제 실패(S3 lifecycle 이 정리한다): {}", key, ex);
		}
	}

}
