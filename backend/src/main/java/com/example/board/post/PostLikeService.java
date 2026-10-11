package com.example.board.post;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;

/**
 * 좋아요 (TASK-9#2). 멱등: 이미 누른 좋아요·누르지 않은 취소도 200 이다.
 *
 * <p>B1 정합성: {@code post_likes} 의 PK(post_id, user_id)가 사용자당 1회를 보장하고, {@code posts.like_count} 는
 * {@code INSERT ... ON CONFLICT DO NOTHING}·{@code DELETE} 의 영향 행 수가 1일 때만 같은 트랜잭션에서 ±1 한다. 같은 쌍의 동시
 * INSERT 는 PostgreSQL 이 먼저 들어간 행의 커밋을 기다렸다가 충돌(0행)로 처리하고, 동시 DELETE 는 한쪽만 1행을 지운다. 그래서 카운터는
 * 항상 행 수와 같다. 카운터 UPDATE 는 글 행 잠금으로 직렬화된다(읽고-쓰기가 아니라 {@code like_count + 1}).
 *
 * <p>대상 글은 보이는 글(삭제·숨김 아님)이어야 한다. 아니면 404 {@code post_not_found}(ADMIN 포함).
 */
@Service
public class PostLikeService {

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public PostLikeService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public record LikeResponse(boolean liked, long likeCount) {
	}

	@Transactional
	public LikeResponse like(Long postId, AuthUser user) {
		requireVisible(postId);
		int inserted = jdbc.update(
				"insert into post_likes (post_id, user_id, created_at) values (?, ?, ?) on conflict do nothing", postId,
				user.id(), Timestamp.from(clock.instant()));
		return new LikeResponse(true, inserted == 1 ? adjust(postId, 1) : count(postId));
	}

	@Transactional
	public LikeResponse unlike(Long postId, AuthUser user) {
		requireVisible(postId);
		int deleted = jdbc.update("delete from post_likes where post_id = ? and user_id = ?", postId, user.id());
		return new LikeResponse(false, deleted == 1 ? adjust(postId, -1) : count(postId));
	}

	/** 보는 사람이 이 글에 좋아요를 눌렀는지. 익명은 false. */
	@Transactional(readOnly = true)
	public boolean isLiked(Long postId, AuthUser viewer) {
		if (viewer == null) {
			return false;
		}
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from post_likes where post_id = ? and user_id = ?)", Boolean.class, postId,
				viewer.id()));
	}

	private void requireVisible(Long postId) {
		Boolean visible = jdbc
			.query("select deleted_at is null and hidden_at is null from posts where id = ?",
					rs -> rs.next() ? rs.getBoolean(1) : null, postId);
		if (!Boolean.TRUE.equals(visible)) {
			throw PostService.notFound();
		}
	}

	private long adjust(Long postId, int delta) {
		return jdbc.queryForObject("update posts set like_count = like_count + ? where id = ? returning like_count",
				Long.class, delta, postId);
	}

	private long count(Long postId) {
		return jdbc.queryForObject("select like_count from posts where id = ?", Long.class, postId);
	}

}
