package com.example.board.attachment;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.board.security.OwnedResource;

/**
 * 이미지 첨부 메타데이터 (TASK-11). 객체 자체는 S3 의 {@link #getS3Key()} 에 있다. 글에는 {@code postId} 로만 이어 둔다(글 쪽
 * 연관관계를 두지 않는다).
 */
@Entity
@Table(name = "attachments")
public class Attachment implements OwnedResource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "owner_id", nullable = false, updatable = false)
	private Long ownerId;

	@Column(name = "post_id")
	private Long postId;

	@Column(name = "s3_key", nullable = false, updatable = false, length = 300)
	private String s3Key;

	@Column(name = "content_type", nullable = false, updatable = false, length = 50)
	private String contentType;

	@Column(nullable = false, updatable = false)
	private long size;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AttachmentStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	protected Attachment() {
	}

	private Attachment(Long ownerId, String s3Key, String contentType, long size, Instant createdAt) {
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.s3Key = Objects.requireNonNull(s3Key, "s3Key");
		this.contentType = Objects.requireNonNull(contentType, "contentType");
		this.size = size;
		this.status = AttachmentStatus.PENDING;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	public static Attachment pending(Long ownerId, String s3Key, String contentType, long size, Instant now) {
		return new Attachment(ownerId, s3Key, contentType, size, now);
	}

	public void confirm(Instant now) {
		if (status == AttachmentStatus.PENDING) {
			this.status = AttachmentStatus.CONFIRMED;
			this.confirmedAt = now;
		}
	}

	public void attachTo(Long postId) {
		if (status != AttachmentStatus.CONFIRMED) {
			throw new IllegalStateException("확정되지 않은 첨부는 글에 붙일 수 없다");
		}
		this.postId = postId;
	}

	public void detach() {
		this.postId = null;
	}

	public Long getId() {
		return id;
	}

	@Override
	public Long getOwnerId() {
		return ownerId;
	}

	public Long getPostId() {
		return postId;
	}

	public String getS3Key() {
		return s3Key;
	}

	public String getContentType() {
		return contentType;
	}

	public long getSize() {
		return size;
	}

	public AttachmentStatus getStatus() {
		return status;
	}

	public boolean isConfirmed() {
		return status == AttachmentStatus.CONFIRMED;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getConfirmedAt() {
		return confirmedAt;
	}

}
