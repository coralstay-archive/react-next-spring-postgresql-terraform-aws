package com.example.board.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 미확정 첨부 정리 (TASK-11#4). {@code pending-ttl}(기본 24시간)보다 오래된 PENDING 행을 지운다. 객체 삭제는 best-effort 다 — 실패해도
 * 보통은 S3 lifecycle(status=pending 태그 1일 만료)이 지운다. 단 confirm 이 태그를 지운 뒤 확정(markConfirmed)에 실패해 PENDING 으로 남은
 * 첨부는 객체에 태그가 없어 lifecycle 도 지우지 않는다 — 그래서 삭제 실패는 키를 WARN 으로 남기고 손으로 지우라고 적는다(어느 경우인지 태그를
 * 다시 읽어 가리지 않는다). 확정된 첨부는 건드리지 않는다.
 *
 * <p>순서 (TASK-26#2, BE-33): 행을 먼저 조건부로 지우고({@code status = PENDING} 이고 아직 만료, 한 행씩), 실제로 지운 행의 객체만 지운다.
 * 대상을 고른 뒤 confirm 이 먼저 확정한 행은 남고 그 객체도 건드리지 않는다 → CONFIRMED 첨부는 항상 객체가 있다(B1). 반대로 행을 지운 뒤
 * confirm 이 확정하려 하면 0행이라 confirm 쪽이 객체를 지운다.
 */
@Component
public class PendingAttachmentCleanup {

	static final int BATCH = 500;

	/** ShedLock 락 이름 (TASK-26#5). */
	static final String LOCK = "pending-attachment-cleanup";

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

	/**
	 * 주기 실행 (TASK-26#5): 인스턴스가 여럿이어도 한 번에 하나만 돈다(ShedLock {@value #LOCK}). 락을 못 잡으면 건너뛴다.
	 */
	@Scheduled(fixedDelayString = "${app.uploads.cleanup-interval:PT1H}",
			initialDelayString = "${app.uploads.cleanup-interval:PT1H}")
	@SchedulerLock(name = LOCK, lockAtMostFor = "PT30M", lockAtLeastFor = "${app.scheduling.lock-at-least-for:PT10S}")
	public void run() {
		cleanUp();
	}

	/**
	 * 지운 행 수. 한 번에 {@value #BATCH}개씩, 남은 게 없거나 실행당 예산({@link CleanupBudget}, TASK-26#4)을 다 쓸 때까지. 락 없이 바로
	 * 돈다(테스트·수동 실행용).
	 */
	public int cleanUp() {
		Instant start = clock.instant();
		Instant cutoff = start.minus(properties.pendingTtl());
		CleanupBudget budget = new CleanupBudget(clock, start, properties);
		int removed = 0;
		sweep: while (true) {
			List<Attachment> stale = attachments.findPendingCreatedBefore(cutoff, Limit.of(BATCH));
			if (stale.isEmpty()) {
				break;
			}
			for (Attachment attachment : stale) {
				if (!budget.tryUse()) {
					log.info("미확정 첨부 정리: 실행당 예산(최대 {}건·{})을 다 써서 멈춤 — 남은 것은 다음 실행", properties.cleanupMaxItems(),
							properties.cleanupMaxDuration());
					break sweep;
				}
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
			// S3 오류 원인은 저장소가 이미 남겼다(TASK-26#1). 태그가 남았으면 lifecycle 이 지우지만, confirm 이 태그를 지운 뒤 확정에 실패한
			// 객체면 영구 고아다 — 확인할 수 없으니 손으로 지우라고 남긴다
			log.warn("미확정 첨부 객체 삭제 실패(행은 지움 — status=pending 태그가 없으면 lifecycle 도 지우지 않으므로 객체를 손으로 지워야 한다)"
					+ " key={} causes={}", key, ex.getClass().getSimpleName());
		}
	}

}
