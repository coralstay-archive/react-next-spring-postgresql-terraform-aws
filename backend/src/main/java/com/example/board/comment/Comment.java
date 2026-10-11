package com.example.board.comment;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.example.board.post.Post;
import com.example.board.security.OwnedResource;
import com.example.board.user.User;

/** 댓글 (TASK-9#1). 삭제는 soft delete({@code deletedAt}) — 목록에는 자리표시로 남는다. */
@Entity
@Table(name = "comments")
public class Comment implements OwnedResource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false, updatable = false)
	private Post post;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false, updatable = false)
	private User author;

	@Column(nullable = false, length = 2000)
	private String content;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected Comment() {
	}

	private Comment(Post post, User author, String content, Instant now) {
		this.post = Objects.requireNonNull(post, "post");
		this.author = Objects.requireNonNull(author, "author");
		this.content = Objects.requireNonNull(content, "content");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public static Comment write(Post post, User author, String content, Instant now) {
		return new Comment(post, author, content, now);
	}

	public void softDelete(Instant now) {
		if (this.deletedAt == null) {
			this.deletedAt = now;
			this.updatedAt = now;
		}
	}

	public Long getId() {
		return id;
	}

	public Post getPost() {
		return post;
	}

	public User getAuthor() {
		return author;
	}

	@Override
	public Long getOwnerId() {
		return author == null ? null : author.getId();
	}

	public String getContent() {
		return content;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

}
