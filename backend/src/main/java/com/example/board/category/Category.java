package com.example.board.category;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 게시판 카테고리 (TASK-8#1). {@code adminOnly} 카테고리(기본 시드: {@code notice} 공지)는 ADMIN 만 글을 쓰거나 글을 옮겨 올 수
 * 있다. 관리 API(생성·수정·삭제)는 {@code AdminCategoryController} (TASK-12#3).
 */
@Entity
@Table(name = "categories")
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 40, unique = true)
	private String slug;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "admin_only", nullable = false)
	private boolean adminOnly;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Category() {
	}

	private Category(String slug, String name, int sortOrder, boolean adminOnly) {
		this.slug = slug;
		this.name = name;
		this.sortOrder = sortOrder;
		this.adminOnly = adminOnly;
	}

	public static Category create(String slug, String name, int sortOrder, boolean adminOnly) {
		return new Category(slug, name, sortOrder, adminOnly);
	}

	/** 관리자 수정 (TASK-12#3). null 인 값은 그대로 둔다. */
	public void update(String slug, String name, Integer sortOrder, Boolean adminOnly) {
		if (slug != null) {
			this.slug = slug;
		}
		if (name != null) {
			this.name = name;
		}
		if (sortOrder != null) {
			this.sortOrder = sortOrder;
		}
		if (adminOnly != null) {
			this.adminOnly = adminOnly;
		}
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getSlug() {
		return slug;
	}

	public String getName() {
		return name;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public boolean isAdminOnly() {
		return adminOnly;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
