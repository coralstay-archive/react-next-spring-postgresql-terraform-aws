package com.example.board.post;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.example.board.category.Category;
import com.example.board.security.OwnedResource;
import com.example.board.tag.Tag;
import com.example.board.user.User;

/**
 * 게시글 (TASK-8#1). 삭제는 soft delete({@code deletedAt}), 관리자 숨김은 {@code hiddenAt}. 둘 중 하나라도 있으면 ADMIN 이 아닌
 * 사용자에게는 없는 글이다(B2).
 */
@Entity
@Table(name = "posts")
public class Post implements OwnedResource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false, updatable = false)
	private User author;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id")
	private Category category;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"),
			inverseJoinColumns = @JoinColumn(name = "tag_id"))
	@OrderBy("name")
	private Set<Tag> tags = new LinkedHashSet<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "hidden_at")
	private Instant hiddenAt;

	/**
	 * 좋아요 수 (TASK-9#2). {@code post_likes} 행을 실제로 넣거나 지운 경우에만 SQL 로 ±1 한다({@link PostLikeService}). 엔티티 저장이
	 * 덮어쓰지 않도록 읽기 전용으로 매핑한다.
	 */
	@Column(name = "like_count", nullable = false, insertable = false, updatable = false)
	private int likeCount;

	protected Post() {
	}

	private Post(User author, Category category, String title, String content) {
		this.author = Objects.requireNonNull(author, "author");
		this.category = category;
		this.title = Objects.requireNonNull(title, "title");
		this.content = Objects.requireNonNull(content, "content");
	}

	public static Post write(User author, Category category, String title, String content) {
		return new Post(author, category, title, content);
	}

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void changeTitle(String title) {
		this.title = Objects.requireNonNull(title, "title");
	}

	public void changeContent(String content) {
		this.content = Objects.requireNonNull(content, "content");
	}

	public void changeCategory(Category category) {
		this.category = category;
	}

	public void replaceTags(Collection<Tag> tags) {
		this.tags.clear();
		this.tags.addAll(tags);
	}

	/** 수정 시각을 갱신한다. 태그만 바뀐 경우처럼 엔티티 컬럼이 그대로여도 수정으로 친다. */
	public void touch(Instant now) {
		this.updatedAt = now;
	}

	public void softDelete(Instant now) {
		if (this.deletedAt == null) {
			this.deletedAt = now;
		}
	}

	public Long getId() {
		return id;
	}

	public User getAuthor() {
		return author;
	}

	@Override
	public Long getOwnerId() {
		return author == null ? null : author.getId();
	}

	public Category getCategory() {
		return category;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public Set<Tag> getTags() {
		return tags;
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

	public Instant getHiddenAt() {
		return hiddenAt;
	}

	public int getLikeCount() {
		return likeCount;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isHidden() {
		return hiddenAt != null;
	}

	/** 삭제·숨김이 아닌 글(일반 사용자에게 보이는 글, B2). */
	public boolean isVisible() {
		return deletedAt == null && hiddenAt == null;
	}

}
