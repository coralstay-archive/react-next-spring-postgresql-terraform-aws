package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.MutableClock;
import com.example.board.user.User;

/**
 * TASK-22#2 (doc-2 BE-31) 글·댓글 작성 사용자별 한도. 기본: 글 1분 3회·1시간 30회, 댓글 1분 10회. 넘으면 429 rate_limited +
 * Retry-After. ADMIN 도 예외가 아니다. 실패한(검증 오류) 작성은 한도를 소모하지 않는다.
 */
@IntegrationTest
class WriteRateLimitTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private static void assertRateLimited(HttpResponse<String> response, long minRetry, long maxRetry) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
		assertThat(response.body()).contains("\"error\":\"rate_limited\"");
		long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(minRetry, maxRetry);
	}

	private HttpResponse<String> post(User user, String category) {
		return api.post("/posts")
			.bearer(fixtures.bearer(user))
			.json("{\"title\":\"제목\",\"content\":\"본문\",\"categorySlug\":" + AuthClient.quote(category) + "}")
			.send();
	}

	private HttpResponse<String> comment(User user, long postId) {
		return api.post("/posts/" + postId + "/comments").bearer(fixtures.bearer(user)).json("{\"content\":\"댓글\"}")
			.send();
	}

	private long postsBy(User user) {
		return jdbc.queryForObject("select count(*) from posts where author_id = ?", Long.class, user.getId());
	}

	@Test
	@DisplayName("TASK-22#2 글은 사용자별 1분에 3개: 4번째는 429 + Retry-After, 다른 사용자는 된다, 1분 뒤 다시 된다")
	void postsPerMinute() {
		User user = fixtures.user();
		String category = board.category();
		for (int i = 0; i < 3; i++) {
			assertThat(post(user, category).statusCode()).as("post %d", i + 1).isEqualTo(201);
		}

		assertRateLimited(post(user, category), 55, 60);
		assertThat(postsBy(user)).isEqualTo(3);
		assertThat(post(fixtures.user(), category).statusCode()).isEqualTo(201);
		clock.advance(Duration.ofMinutes(1));
		assertThat(post(user, category).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-22#2 글은 사용자별 1시간에 30개: 분당 한도를 지켜도 31번째는 429(Retry-After 는 첫 글이 1시간 창을 벗어날 때까지)")
	void postsPerHour() {
		User user = fixtures.user();
		String category = board.category();
		for (int i = 0; i < 30; i++) {
			assertThat(post(user, category).statusCode()).as("post %d", i + 1).isEqualTo(201);
			if (i % 3 == 2) {
				clock.advance(Duration.ofSeconds(61));
			}
		}

		// 첫 글은 10*61초 전 → 1시간 - 610초 뒤에 풀린다
		long expected = Duration.ofHours(1).minusSeconds(610).toSeconds();
		assertRateLimited(post(user, category), expected - 5, expected + 1);
		clock.advance(Duration.ofSeconds(expected + 1));
		assertThat(post(user, category).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-22#2 ADMIN 도 예외가 아니다(글 1분 3개)")
	void adminIsNotExempt() {
		User admin = fixtures.admin();
		String category = board.category();
		for (int i = 0; i < 3; i++) {
			assertThat(post(admin, category).statusCode()).isEqualTo(201);
		}

		assertThat(post(admin, category).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#2 실패한 작성(없는 카테고리 400)은 한도를 소모하지 않는다")
	void failedWritesDoNotCount() {
		User user = fixtures.user();
		String category = board.category();
		for (int i = 0; i < 5; i++) {
			assertThat(post(user, "no-such-" + i).statusCode()).isEqualTo(400);
		}

		for (int i = 0; i < 3; i++) {
			assertThat(post(user, category).statusCode()).as("post %d", i + 1).isEqualTo(201);
		}
	}

	@Test
	@DisplayName("TASK-22#2 댓글은 사용자별 1분에 10개(글이 달라도): 11번째는 429, 1분 뒤 다시 된다")
	void commentsPerMinute() {
		User user = fixtures.user();
		String category = board.category();
		long first = board.post(fixtures.user().getId(), category, "글1");
		long second = board.post(fixtures.user().getId(), category, "글2");
		for (int i = 0; i < 10; i++) {
			assertThat(comment(user, i % 2 == 0 ? first : second).statusCode()).as("comment %d", i + 1).isEqualTo(201);
		}

		assertRateLimited(comment(user, first), 55, 60);
		assertThat(comment(fixtures.user(), first).statusCode()).isEqualTo(201);
		clock.advance(Duration.ofMinutes(1));
		assertThat(comment(user, first).statusCode()).isEqualTo(201);
	}

}
