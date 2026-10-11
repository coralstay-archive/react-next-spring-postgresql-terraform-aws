package com.example.board.post;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회수 집계 (TASK-9#3, B2). 같은 방문자({@link ViewerKeys})의 조회는 {@link #WINDOW} 에 한 번만 센다.
 *
 * <p>{@code post_views} upsert 의 영향 행 수로 판정한다: 처음 보는 방문자면 INSERT(1행), 마지막으로 센 지 30분이 지났으면
 * {@code DO UPDATE ... WHERE viewed_at <= now-30m}(1행), 30분 안이면 0행. 1행일 때만 같은 트랜잭션에서 {@code view_count + 1}.
 * 같은 방문자의 동시 조회는 PostgreSQL 이 같은 키 행에서 직렬화하고, 뒤의 것은 먼저 커밋된 viewed_at 으로 WHERE 를 다시 평가해
 * 0행이 된다. 시각은 Clock 빈(테스트는 MutableClock)에서 받는다.
 *
 * <p>상세 읽기(읽기 전용 트랜잭션)와 분리된 자기 트랜잭션에서 돈다 — 컨트롤러가 읽기를 마친 뒤 부른다.
 */
@Service
public class PostViewService {

	public static final Duration WINDOW = Duration.ofMinutes(30);

	private final JdbcTemplate jdbc;

	private final Clock clock;

	public PostViewService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 이번 조회를 집계했으면 true. */
	@Transactional
	public boolean record(Long postId, String viewerKey) {
		Instant now = clock.instant();
		int counted = jdbc.update("""
				insert into post_views (post_id, viewer_key, viewed_at) values (?, ?, ?)
				on conflict (post_id, viewer_key) do update set viewed_at = excluded.viewed_at
				where post_views.viewed_at <= ?
				""", postId, viewerKey, Timestamp.from(now), Timestamp.from(now.minus(WINDOW)));
		if (counted == 1) {
			jdbc.update("update posts set view_count = view_count + 1 where id = ?", postId);
			return true;
		}
		return false;
	}

}
