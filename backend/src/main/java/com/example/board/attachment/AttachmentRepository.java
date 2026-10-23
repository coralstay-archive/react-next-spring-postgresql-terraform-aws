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

}
