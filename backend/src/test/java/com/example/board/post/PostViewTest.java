package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * TASK-9#3 GET /posts/{id} 조회수 증가 + TASK-9#B2 같은 사용자(로그인) 또는 같은 익명 브라우저(VID 쿠키)의 조회는 30분에 한 번만
 * 집계한다.
 */
@IntegrationTest
class PostViewTest {

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

	private long views(long postId) {
		return jdbc.queryForObject("select view_count from posts where id = ?", Long.class, postId);
	}

	private long post() {
		return board.post(fixtures.user().getId(), null, "조회");
	}

	private HttpResponse<String> view(long postId, Api.Request request) {
		HttpResponse<String> response = request.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return response;
	}

	@Test
	@DisplayName("TASK-9#3 익명 첫 조회: view_count +1, 응답 viewCount 에 반영, VID 쿠키(HttpOnly, SameSite=Lax, 30일) 발급, 세션은 만들지 않는다")
	void anonymousFirstView() {
		long postId = post();

		HttpResponse<String> response = view(postId, api.get("/posts/" + postId));

		assertThat(views(postId)).isEqualTo(1);
		assertThat(AuthClient.json(response).get("viewCount").asLong()).isEqualTo(1);
		String cookie = Api.setCookie(response, "VID").orElseThrow();
		assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Max-Age=2592000")
			.contains("Path=/");
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
	}

	@Test
	@DisplayName("TASK-9#B2 같은 VID 쿠키로 30분 안에 다시 보면 집계하지 않고, 30분이 지나면 다시 집계한다")
	void anonymousThirtyMinuteWindow() {
		long postId = post();
		String vid = Api.cookie(view(postId, api.get("/posts/" + postId)), "VID").orElseThrow();

		HttpResponse<String> again = view(postId, api.get("/posts/" + postId).cookie("VID", vid));
		assertThat(views(postId)).isEqualTo(1);
		assertThat(AuthClient.json(again).get("viewCount").asLong()).isEqualTo(1);
		assertThat(Api.setCookie(again, "VID")).as("유효한 VID 는 다시 발급하지 않는다").isEmpty();

		clock.advance(Duration.ofMinutes(29));
		view(postId, api.get("/posts/" + postId).cookie("VID", vid));
		assertThat(views(postId)).isEqualTo(1);

		clock.advance(Duration.ofMinutes(1));
		HttpResponse<String> later = view(postId, api.get("/posts/" + postId).cookie("VID", vid));
		assertThat(views(postId)).isEqualTo(2);
		assertThat(AuthClient.json(later).get("viewCount").asLong()).isEqualTo(2);

		view(postId, api.get("/posts/" + postId).cookie("VID", vid));
		assertThat(views(postId)).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-9#B2 로그인 사용자는 사용자 id 로 센다 — 세션·Bearer·VID 가 달라도 30분에 1회, 다른 사용자는 따로 센다")
	void loggedInCountsPerUser() {
		long postId = post();
		User reader = fixtures.user();

		view(postId, api.get("/posts/" + postId).bearer(fixtures.bearer(reader)));
		view(postId, api.get("/posts/" + postId).cookie(fixtures.sessionCookie(reader)));
		view(postId, api.get("/posts/" + postId).bearer(fixtures.bearer(reader)).cookie("VID", "AAAAAAAAAAAAAAAAAAAAAA"));
		assertThat(views(postId)).isEqualTo(1);

		view(postId, api.get("/posts/" + postId).bearer(fixtures.bearer(fixtures.user())));
		assertThat(views(postId)).isEqualTo(2);

		clock.advance(Duration.ofMinutes(31));
		view(postId, api.get("/posts/" + postId).bearer(fixtures.bearer(reader)));
		assertThat(views(postId)).isEqualTo(3);
	}

	@Test
	@DisplayName("TASK-9#B2 post_views 에는 익명 쿠키 원문이 아니라 해시만 저장한다(s:<sha256>), 로그인은 u:<id>")
	void viewerKeys() {
		long postId = post();
		String vid = Api.cookie(view(postId, api.get("/posts/" + postId)), "VID").orElseThrow();
		User reader = fixtures.user();
		view(postId, api.get("/posts/" + postId).bearer(fixtures.bearer(reader)));

		List<String> keys = jdbc.queryForList("select viewer_key from post_views where post_id = ? order by viewer_key",
				String.class, postId);
		assertThat(keys).hasSize(2);
		assertThat(keys).contains("u:" + reader.getId());
		String anonymous = keys.stream().filter(k -> k.startsWith("s:")).findFirst().orElseThrow();
		assertThat(anonymous).doesNotContain(vid).hasSize(2 + 64);
	}

	@Test
	@DisplayName("TASK-9#B2 형식이 틀린 VID 쿠키는 새로 발급하고 새 방문자로 센다")
	void invalidVidReplaced() {
		long postId = post();
		HttpResponse<String> response = view(postId, api.get("/posts/" + postId).cookie("VID", "bad value!"));
		assertThat(Api.cookie(response, "VID")).hasValueSatisfying(v -> assertThat(v).isNotEqualTo("bad value!"));
		assertThat(views(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-9#B2 같은 방문자의 동시 조회 10건은 1회만 집계한다")
	void concurrentSameViewer() throws Exception {
		long postId = post();
		String token = fixtures.bearer(fixtures.user());
		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return api.get("/posts/" + postId).bearer(token).send().statusCode();
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
		assertThat(views(postId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-9#3 404 인 조회(없는·삭제·숨김 글)와 ADMIN 의 삭제·숨김 글 조회는 집계하지 않는다")
	void invisibleNotCounted() {
		long deleted = post();
		long hidden = post();
		board.delete(deleted);
		board.hide(hidden);
		String admin = fixtures.bearer(fixtures.admin());

		for (long postId : new long[] { deleted, hidden }) {
			assertThat(api.get("/posts/" + postId).send().statusCode()).isEqualTo(404);
			assertThat(api.get("/posts/" + postId).bearer(admin).send().statusCode()).isEqualTo(200);
			assertThat(views(postId)).isZero();
		}
		assertThat(api.get("/posts/" + Long.MAX_VALUE).send().statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("TASK-9#3 목록 항목에 viewCount, 글 수정(PATCH)이 조회수를 덮어쓰지 않는다")
	void listAndUpdate() {
		String category = board.category();
		User author = fixtures.user();
		long postId = board.post(author.getId(), category, "목록");
		view(postId, api.get("/posts/" + postId));

		HttpResponse<String> patched = api.patch("/posts/" + postId).bearer(fixtures.bearer(author))
			.json("{\"title\":\"새 제목\"}").send();
		assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
		assertThat(views(postId)).isEqualTo(1);

		HttpResponse<String> list = api.get("/posts?category=" + category).send();
		assertThat(AuthClient.json(list).get("items").get(0).get("viewCount").asLong()).isEqualTo(1);
	}

}
