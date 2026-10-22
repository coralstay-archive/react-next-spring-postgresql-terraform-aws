package com.example.board.post;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

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
 * <p>대상 글은 보이는 글(삭제·숨김 아님)이어야 한다. 아니면 404 {@code post_not_found}(ADMIN 포함). 확인은 글 행을 잠그고 읽는다
 * (TASK-23#4): 확인 뒤의 숨김·삭제 UPDATE 는 이 트랜잭션이 끝날 때까지 기다리고, 먼저 커밋된 숨김·삭제는 여기서 보인다. 글 행이 없으면
 * (확인 전이든 카운터 갱신 때든) 500 이 아니라 404 다. 잠금은 댓글 작성의 {@code FOR SHARE} 가 아니라 {@code FOR NO KEY UPDATE} 다 — 좋아요는
 * 같은 행의 카운터를 이어서 UPDATE 하므로, 공유 잠금을 쥔 동시 좋아요 둘이 서로의 공유 잠금 때문에 UPDATE 로 올리지 못해 교착(40P01)이 난다.
 * {@code FOR NO KEY UPDATE} 는 같은 글의 좋아요끼리 처음부터 줄 세우고(어차피 카운터 UPDATE 에서 줄 선다), 댓글·좋아요 행 INSERT 의
 * FK 확인({@code FOR KEY SHARE})은 막지 않는다.
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
			.query("select deleted_at is null and hidden_at is null from posts where id = ? for no key update",
					rs -> rs.next() ? rs.getBoolean(1) : null, postId);
		if (!Boolean.TRUE.equals(visible)) {
			throw PostService.notFound();
		}
	}

	private long adjust(Long postId, int delta) {
		return single(jdbc.query("update posts set like_count = like_count + ? where id = ? returning like_count",
				(rs, row) -> rs.getLong(1), delta, postId));
	}

	private long count(Long postId) {
		return single(jdbc.query("select like_count from posts where id = ?", (rs, row) -> rs.getLong(1), postId));
	}

	/** 글 행이 없으면 404 (TASK-23#4: 예전에는 queryForObject 가 EmptyResultDataAccessException → 500). */
	private static long single(List<Long> rows) {
		if (rows.isEmpty()) {
			throw PostService.notFound();
		}
		return rows.get(0);
	}

}
