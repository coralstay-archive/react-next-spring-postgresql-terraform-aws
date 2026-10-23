package com.example.board.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 글에 붙지 않은 확정 첨부와 삭제된 글의 첨부 정리 (TASK-26#4, BE-36). 지연 삭제 정책: 유예 기간({@code app.uploads.orphan-grace-period},
 * 기본 7일)이 지나면 행을 지우고 S3 객체를 지운다. 대상은
 * <ul>
 * <li>글 수정으로 뗀 첨부: 뗀 시각({@code detached_at})부터</li>
 * <li>확정만 하고 글에 붙이지 않은 첨부: 확정 시각부터</li>
 * <li>삭제된(soft delete) 글의 첨부: 글의 삭제 시각부터. 유예 안에 관리자가 복구하면 손대지 않으므로 이미지가 그대로다(B2). 유예 뒤 복구한
 * 글에는 이미지가 없다</li>
 * </ul>
 * 숨김 글의 첨부는 대상이 아니다(숨김 해제가 흔하다). 배포 전 데이터도 배포 시각부터 유예한다(V15): 그 전에 뗀 첨부는 {@code detached_at} 을
 * 배포 시각으로 채웠고, 삭제 글 첨부는 정리 시작 시각({@code attachment_cleanup_start}) + 유예 전에는 지우지 않는다.
 *
 * <p>공개 URL({@code /uploads/...})은 CloudFront 가 S3 의 같은 키를 읽어 주므로 객체를 지우면 열리지 않는다. 단 엣지에 이미 캐시된 사본은
 * CloudFront 캐시 TTL(CachingOptimized 기본 24시간) 동안 더 나갈 수 있다(docs/api.md).
 *
 * <p>순서: 행을 조건부로 먼저 지우고(그사이 다시 붙였거나 복구했으면 0행 — 건너뜀), 지운 행의 객체를 지운다 → CONFIRMED 첨부는 항상 객체가
 * 있다(TASK-26 B1). 객체 삭제가 실패하면(S3 장애) 그 객체는 태그가 없어 lifecycle 도 지우지 않으므로 키를 WARN 으로 남기고, 장애 중에 행만
 * 계속 지우지 않도록 이번 실행을 멈춘다.
 *
 * <p>실행당 예산({@link CleanupBudget}: {@code app.uploads.cleanup-max-items} 기본 5000건, {@code cleanup-max-duration} 기본 10분)을
 * 다 쓰면 멈추고 남은 것은 다음 실행이 한다 — 스케줄 락 lockAtMostFor(30분)를 넘겨 다른 인스턴스와 겹쳐 돌지 않게.
 */
@Component
public class OrphanAttachmentCleanup {

	static final int BATCH = 500;

	/** ShedLock 락 이름 (TASK-26#5). */
	static final String LOCK = "orphan-attachment-cleanup";

	private static final Logger log = LoggerFactory.getLogger(OrphanAttachmentCleanup.class);

	private final AttachmentRepository attachments;

	private final UploadStorage storage;

	private final UploadProperties properties;

	private final Clock clock;

	public OrphanAttachmentCleanup(AttachmentRepository attachments, UploadStorage storage,
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

	/** 락 없이 바로 돈다(테스트·수동 실행용). 지운 첨부 수. 버킷이 없으면(업로드 꺼짐) 아무것도 하지 않는다 — 객체를 지울 수 없는데 행만 지우면 객체가 영구 고아가 된다. */
	public int cleanUp() {
		if (!storage.available()) {
			return 0;
		}
		Instant start = clock.instant();
		Instant cutoff = start.minus(properties.orphanGracePeriod());
		Sweep sweep = new Sweep(cutoff, new CleanupBudget(clock, start, properties));
		sweep.drain(limit -> attachments.findUnlinkedBefore(cutoff, limit));
		sweep.drain(limit -> attachments.findOfPostsDeletedBefore(cutoff, limit));
		if (sweep.removed > 0) {
			log.info("연결 해제·삭제 글 첨부 {}건 정리", sweep.removed);
		}
		if (sweep.budget.exhausted()) {
			log.info("연결 해제·삭제 글 첨부 정리: 실행당 예산(최대 {}건·{})을 다 써서 멈춤 — 남은 것은 다음 실행", properties.cleanupMaxItems(),
					properties.cleanupMaxDuration());
		}
		return sweep.removed;
	}

	private final class Sweep {

		private final Instant cutoff;

		private final CleanupBudget budget;

		private int removed;

		private boolean storageFailed;

		Sweep(Instant cutoff, CleanupBudget budget) {
			this.cutoff = cutoff;
			this.budget = budget;
		}

		void drain(Function<Integer, List<Attachment>> candidates) {
			while (!storageFailed && !budget.exhausted()) {
				List<Attachment> batch = candidates.apply(BATCH);
				for (Attachment attachment : batch) {
					if (storageFailed || !budget.tryUse()) {
						return;
					}
					if (attachments.deleteIfOrphanedBefore(attachment.getId(), cutoff) == 1) {
						removed++;
						deleteObject(attachment.getS3Key());
					}
				}
				if (batch.size() < BATCH) {
					return;
				}
			}
		}

		private void deleteObject(String key) {
			try {
				storage.delete(key);
			}
			catch (RuntimeException ex) {
				storageFailed = true;
				// S3 오류 원인은 저장소가 이미 남겼다(TASK-26#1). 행은 지웠으므로 이 키는 손으로 지워야 한다
				log.warn("첨부 객체 삭제 실패, 이번 정리를 멈춘다(행은 지움 — 객체를 손으로 지워야 한다) key={} causes={}", key,
						ex.getClass().getSimpleName());
			}
		}

	}

}
