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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

	/** 조회수 (TASK-9#3). {@link PostViewService} 가 SQL 로만 +1 한다. 읽기 전용 매핑. */
	@Column(name = "view_count", nullable = false, insertable = false, updatable = false)
	private long viewCount;

	/**
	 * 낙관적 잠금 (TASK-23#3, B1). 작성자 수정·삭제와 관리자 숨김·복구가 겹치면 늦게 커밋하는 쪽이 409 {@code conflict} 다. 엔티티는 모든
	 * 컬럼을 UPDATE 하므로 이게 없으면 늦은 쪽이 먼저 커밋된 숨김·삭제를 옛 값으로 되돌린다. 클라이언트는 버전을 보내지 않는다(서버
	 * 트랜잭션끼리 겹친 경쟁만 잡는다, docs/api.md).
	 */
	@Version
	@Column(nullable = false)
	private long version;

	protected Post() {
	}

	private Post(User author, Category category, String title, String content, Instant now) {
		this.author = Objects.requireNonNull(author, "author");
		this.category = category;
		this.title = Objects.requireNonNull(title, "title");
		this.content = Objects.requireNonNull(content, "content");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	/** 새 글. {@code now} 는 서비스의 Clock 빈 시각(TASK-23#6) — 저장 직후 응답과 다시 읽은 값이 같다. */
	public static Post write(User author, Category category, String title, String content, Instant now) {
		return new Post(author, category, title, content, now);
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

	/** 관리자 숨김 (TASK-12#2). 이미 숨겨져 있으면 처음 숨긴 시각을 유지한다. */
	public void hide(Instant now) {
		if (this.hiddenAt == null) {
			this.hiddenAt = now;
		}
	}

	/** 관리자 숨김 해제 (TASK-12#2). */
	public void unhide() {
		this.hiddenAt = null;
	}

	/** 삭제 복구 (TASK-12#2). 숨김 여부는 건드리지 않는다. */
	public void restore() {
		this.deletedAt = null;
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

	public long getViewCount() {
		return viewCount;
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
