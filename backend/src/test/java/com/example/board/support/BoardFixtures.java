package com.example.board.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 게시판 테스트 픽스처 (TASK-8). 글·카테고리를 SQL 로 직접 만든다(쓰기 API 를 거치지 않고 목록·상세·숨김 상태를 준비할 때).
 * 테스트 컨텍스트가 DB 를 공유하므로, 목록 테스트는 {@link #category()} 로 자기만의 카테고리를 만들어 그 안에서 확인한다.
 */
@Component
public class BoardFixtures {

	private final JdbcTemplate jdbc;

	public BoardFixtures(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 겹치지 않는 짧은 이름(태그 30자 제한 안). */
	public static String unique(String prefix) {
		return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	/** 새 카테고리(일반). slug 를 돌려준다. */
	public String category() {
		return category(false);
	}

	public String category(boolean adminOnly) {
		String slug = unique("c-");
		jdbc.update("insert into categories (slug, name, sort_order, admin_only) values (?, ?, 100, ?)", slug,
				"카테고리 " + slug, adminOnly);
		return slug;
	}

	/** 글 하나를 만들고 id 를 돌려준다. createdAt 으로 순서를 정한다. 태그는 이미 정규화된 이름. */
	public long post(long authorId, String categorySlug, String title, String content, Instant createdAt,
			String... tags) {
		Long categoryId = categorySlug == null ? null
				: jdbc.queryForObject("select id from categories where slug = ?", Long.class, categorySlug);
		Timestamp at = Timestamp.from(createdAt);
		Long id = jdbc.queryForObject(
				"insert into posts (author_id, category_id, title, content, created_at, updated_at) values (?, ?, ?, ?, ?, ?) returning id",
				Long.class, authorId, categoryId, title, content, at, at);
		for (String tag : tags) {
			jdbc.update("insert into tags (name) values (?) on conflict (name) do nothing", tag);
			jdbc.update("insert into post_tags (post_id, tag_id) select ?, id from tags where name = ?", id, tag);
		}
		return id;
	}

	public long post(long authorId, String categorySlug, String title, String... tags) {
		return post(authorId, categorySlug, title, "본문 " + title, Instant.now(), tags);
	}

	public void hide(long postId) {
		jdbc.update("update posts set hidden_at = now() where id = ?", postId);
	}

	public void delete(long postId) {
		jdbc.update("update posts set deleted_at = now() where id = ?", postId);
	}

}
