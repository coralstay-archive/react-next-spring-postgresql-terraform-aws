package com.example.board.attachment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

	/** 정리 시작 시각(V15, 배포 시각)이 {@code cutoff} 전이다 — 그 전에는 삭제 글 첨부를 지우지 않는다(TASK-26#4). 행이 없으면 참. */
	String STARTED_BEFORE_CUTOFF = "not exists (select 1 from attachment_cleanup_start s where s.started_at >= :cutoff)";

	/** 사용자의 상태별 첨부 수 (TASK-22#3 미확정 개수 한도). */
	long countByOwnerIdAndStatus(Long ownerId, AttachmentStatus status);

	/** 글에 붙은 첨부(올린 순서). */
	List<Attachment> findByPostIdOrderByIdAsc(Long postId);

	/** 글 연결(TASK-11 B2) 전에 행을 잠근다. 같은 첨부를 두 글에 동시에 붙이지 못하게 한다. id 순으로 잠가 교착을 피한다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Attachment a where a.id in :ids order by a.id")
	List<Attachment> findAllForUpdate(Collection<Long> ids);

	/**
	 * PENDING → CONFIRMED. {@code cutoff}(지금 - pending-ttl) 이전에 만든 행은 확정하지 않는다(TASK-26#2). 이미 확정됐거나(경쟁), 정리돼
	 * 없거나, 만료됐으면 0.
	 */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update Attachment a set a.status = com.example.board.attachment.AttachmentStatus.CONFIRMED, a.confirmedAt = :now where a.id = :id and a.status = com.example.board.attachment.AttachmentStatus.PENDING and a.createdAt > :cutoff")
	int markConfirmed(Long id, Instant now, Instant cutoff);

	/** 정리 대상: {@code cutoff} 보다 먼저 만든 PENDING. */
	@Query("select a from Attachment a where a.status = com.example.board.attachment.AttachmentStatus.PENDING and a.createdAt < :cutoff order by a.id")
	List<Attachment> findPendingCreatedBefore(Instant cutoff, Limit limit);

	/**
	 * 행 하나를 아직 만료된 PENDING 일 때만 지운다(TASK-26#2). 그사이 confirm 된 행은 남는다(0). 지운 행 수 — 1 이면 호출자가 객체를 지운다.
	 */
	@Transactional
	@Modifying
	@Query("delete from Attachment a where a.id = :id and a.status = com.example.board.attachment.AttachmentStatus.PENDING and a.createdAt < :cutoff")
	int deleteExpiredPending(Long id, Instant cutoff);

	/** 글에 붙지 않은 확정 첨부 중 {@code cutoff} 전에 뗐거나(detached_at) 붙인 적 없이 확정한(confirmed_at) 것 (TASK-26#4). */
	@Query(value = "select a.* from attachments a where a.status = 'CONFIRMED' and a.post_id is null"
			+ " and coalesce(a.detached_at, a.confirmed_at) < :cutoff order by a.id limit :limit", nativeQuery = true)
	List<Attachment> findUnlinkedBefore(Instant cutoff, int limit);

	/**
	 * {@code cutoff} 전에 삭제된 글의 첨부 (TASK-26#4). 정리 시작 시각(V15 {@code attachment_cleanup_start}, 배포 시각)이 {@code cutoff}
	 * 이후면 없다 — 배포 전에 삭제된 글도 배포 시각부터 유예를 준다.
	 */
	@Query(value = "select a.* from attachments a join posts p on p.id = a.post_id where p.deleted_at < :cutoff"
			+ " and " + STARTED_BEFORE_CUTOFF + " order by a.id limit :limit", nativeQuery = true)
	List<Attachment> findOfPostsDeletedBefore(Instant cutoff, int limit);

	/**
	 * 행 하나를 아직 정리 대상일 때만 지운다 (TASK-26#4): 글에 붙지 않은 채 {@code cutoff} 이전부터였거나, 붙은 글이 {@code cutoff} 이전에
	 * 삭제됐다. 그사이 다시 붙였으면(글 수정이 행을 FOR UPDATE 로 잠근다 — 이 DELETE 는 기다렸다가 바뀐 행으로 조건을 다시 본다) 0. 글은
	 * FOR SHARE 로 읽어 동시 복구(restore)가 먼저 커밋되면 그 결과를 본다. 상관 EXISTS 라 잠그는 글은 이 첨부의 글 한 행뿐이다(비상관
	 * {@code IN (select … for share)} 는 오래된 삭제 글 전부를 매번 잠갔다). 지운 행 수 — 1 이면 호출자가 객체를 지운다.
	 */
	@Transactional
	@Modifying
	@Query(value = "delete from attachments a where a.id = :id and ((a.status = 'CONFIRMED' and a.post_id is null"
			+ " and coalesce(a.detached_at, a.confirmed_at) < :cutoff)"
			+ " or (" + STARTED_BEFORE_CUTOFF + " and exists (select 1 from posts p where p.id = a.post_id"
			+ " and p.deleted_at < :cutoff for share)))", nativeQuery = true)
	int deleteIfOrphanedBefore(Long id, Instant cutoff);

}
