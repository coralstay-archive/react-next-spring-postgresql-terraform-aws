package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/** TASK-9#2 POST/DELETE /posts/{id}/like + TASK-9#B1 사용자당 글 하나에 1회, like_count 정합성. */
@IntegrationTest
class PostLikeTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private HttpResponse<String> like(User user, long postId) {
		return api.post("/posts/" + postId + "/like").bearer(fixtures.bearer(user)).send();
	}

	private HttpResponse<String> unlike(User user, long postId) {
		return api.delete("/posts/" + postId + "/like").bearer(fixtures.bearer(user)).send();
	}

	private long storedCount(long postId) {
		return jdbc.queryForObject("select like_count from posts where id = ?", Long.class, postId);
	}

	private long likeRows(long postId) {
		return jdbc.queryForObject("select count(*) from post_likes where post_id = ?", Long.class, postId);
	}

	private static JsonNode json(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	@Test
	@DisplayName("TASK-9#2 POST /posts/{id}/like: 200 {liked:true, likeCount}, DELETE: 200 {liked:false, likeCount}")
	void likeAndUnlike() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "좋아요");
		User fan = fixtures.user();

		JsonNode liked = json(like(fan, postId));
		assertThat(liked.get("liked").asBoolean()).isTrue();
		assertThat(liked.get("likeCount").asLong()).isEqualTo(1);

		JsonNode second = json(like(author, postId));
		assertThat(second.get("likeCount").asLong()).isEqualTo(2);

		JsonNode unliked = json(unlike(fan, postId));
		assertThat(unliked.get("liked").asBoolean()).isFalse();
		assertThat(unliked.get("likeCount").asLong()).isEqualTo(1);
		assertThat(storedCount(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-9#B1 같은 사용자가 여러 번 좋아요해도 1회(멱등), 안 누른 취소도 멱등 — like_count 는 행 수와 같다")
	void idempotent() {
		long postId = board.post(fixtures.user().getId(), null, "멱등");
		User fan = fixtures.user();

		assertThat(json(like(fan, postId)).get("likeCount").asLong()).isEqualTo(1);
		JsonNode again = json(like(fan, postId));
		assertThat(again.get("liked").asBoolean()).isTrue();
		assertThat(again.get("likeCount").asLong()).isEqualTo(1);

		assertThat(json(unlike(fan, postId)).get("likeCount").asLong()).isZero();
		JsonNode unlikeAgain = json(unlike(fan, postId));
		assertThat(unlikeAgain.get("liked").asBoolean()).isFalse();
		assertThat(unlikeAgain.get("likeCount").asLong()).isZero();

		assertThat(storedCount(postId)).isZero();
		assertThat(likeRows(postId)).isZero();
	}

	@Test
	@DisplayName("TASK-9#B1 post_likes 는 (post_id, user_id) PK — 같은 쌍을 두 번 넣을 수 없다")
	void uniquePerUser() {
		User user = fixtures.user();
		long postId = board.post(user.getId(), null, "PK");
		jdbc.update("insert into post_likes (post_id, user_id) values (?, ?)", postId, user.getId());
		int second = jdbc.update("insert into post_likes (post_id, user_id) values (?, ?) on conflict do nothing", postId,
				user.getId());
		assertThat(second).isZero();
	}

	@Test
	@DisplayName("TASK-9#B1 동시 요청: 20명이 동시에 좋아요 + 한 사용자의 동시 좋아요 10건 → like_count = 행 수 = 21")
	void concurrentLikes() throws Exception {
		long postId = board.post(fixtures.user().getId(), null, "동시");
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tokens.add(fixtures.bearer(fixtures.user()));
		}
		String same = fixtures.bearer(fixtures.user());
		for (int i = 0; i < 10; i++) {
			tokens.add(same);
		}

		ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (String token : tokens) {
				Callable<Integer> call = () -> {
					start.await();
					return api.post("/posts/" + postId + "/like").bearer(token).send().statusCode();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(likeRows(postId)).isEqualTo(21);
		assertThat(storedCount(postId)).isEqualTo(21);
	}

	@Test
	@DisplayName("TASK-9#B1 동시 취소: 같은 사용자의 취소 10건이 겹쳐도 like_count 는 한 번만 줄어든다")
	void concurrentUnlikes() throws Exception {
		long postId = board.post(fixtures.user().getId(), null, "동시 취소");
		User other = fixtures.user();
		User fan = fixtures.user();
		like(other, postId);
		like(fan, postId);
		String token = fixtures.bearer(fan);

		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return api.delete("/posts/" + postId + "/like").bearer(token).send().statusCode();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(likeRows(postId)).isEqualTo(1);
		assertThat(storedCount(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-9#2 목록·상세 응답에 likeCount, 상세에는 likedByMe(보는 사람 기준, 익명 false)")
	void countsInResponses() {
		String category = board.category();
		long postId = board.post(fixtures.user().getId(), category, "응답");
		User fan = fixtures.user();
		like(fan, postId);

		JsonNode mine = json(api.get("/posts/" + postId).bearer(fixtures.bearer(fan)).send());
		assertThat(mine.get("likeCount").asLong()).isEqualTo(1);
		assertThat(mine.get("likedByMe").asBoolean()).isTrue();

		JsonNode other = json(api.get("/posts/" + postId).bearer(fixtures.bearer(fixtures.user())).send());
		assertThat(other.get("likedByMe").asBoolean()).isFalse();
		JsonNode anonymous = json(api.get("/posts/" + postId).send());
		assertThat(anonymous.get("likedByMe").asBoolean()).isFalse();
		assertThat(anonymous.get("likeCount").asLong()).isEqualTo(1);

		JsonNode list = json(api.get("/posts?category=" + category).send());
		assertThat(list.get("items").get(0).get("likeCount").asLong()).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-9#2 글 수정(PATCH)이 좋아요 수를 덮어쓰지 않는다")
	void updateKeepsCounter() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "수정");
		like(fixtures.user(), postId);

		HttpResponse<String> patched = api.patch("/posts/" + postId).bearer(fixtures.bearer(author))
			.json("{\"title\":\"새 제목\"}").send();
		assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
		like(fixtures.user(), postId);

		assertThat(storedCount(postId)).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-9#2 없는·삭제·숨김 글은 404 post_not_found, 익명 401, 세션은 CSRF 필요")
	void edgeCases() {
		User user = fixtures.user();
		long deleted = board.post(user.getId(), null, "삭제");
		long hidden = board.post(user.getId(), null, "숨김");
		board.delete(deleted);
		board.hide(hidden);

		for (long postId : new long[] { Long.MAX_VALUE, deleted, hidden }) {
			HttpResponse<String> response = like(user, postId);
			assertThat(response.statusCode()).isEqualTo(404);
			assertThat(error(response)).isEqualTo("post_not_found");
			assertThat(unlike(user, postId).statusCode()).isEqualTo(404);
		}

		long postId = board.post(user.getId(), null, "인증");
		String csrf = Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
		assertThat(api.post("/posts/" + postId + "/like").csrf(csrf).send().statusCode()).isEqualTo(401);
		String session = fixtures.sessionCookie(user);
		assertThat(api.post("/posts/" + postId + "/like").cookie(session).send().statusCode()).isEqualTo(403);
		assertThat(api.post("/posts/" + postId + "/like").cookie(session).csrf(csrf).send().statusCode()).isEqualTo(200);
		assertThat(api.delete("/posts/" + postId + "/like").cookie(session).csrf(csrf).send().statusCode())
			.isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-9#2 정지 계정·제한(step-up) 토큰의 좋아요는 중앙 필터가 403")
	void centralFiltersApply() {
		User user = fixtures.user();
		long postId = board.post(user.getId(), null, "필터");

		HttpResponse<String> suspended = like(fixtures.suspended(), postId);
		assertThat(suspended.statusCode()).isEqualTo(403);
		assertThat(error(suspended)).isEqualTo("suspended");
		HttpResponse<String> stepUp = api.post("/posts/" + postId + "/like").bearer(fixtures.stepUpBearer(user)).send();
		assertThat(stepUp.statusCode()).isEqualTo(403);
		assertThat(error(stepUp)).isEqualTo("step_up_required");
		assertThat(storedCount(postId)).isZero();
	}

}
