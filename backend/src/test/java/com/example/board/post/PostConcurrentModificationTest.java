package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import com.example.board.support.RowLock;
import com.example.board.user.User;

/**
 * TASK-23#3 (BE-27·BE-39) 글 낙관적 잠금. 작성자 수정·삭제와 관리자 숨김이 겹치면 늦은 쪽이 409 conflict 이고, 먼저 커밋된 숨김·삭제가
 * 되살아나지 않는다(B1). 테스트 커넥션이 글 행을 잡아 두 요청을 UPDATE 앞에 순서대로 줄 세운 뒤 풀어서 경쟁 순서를 고정한다.
 */
@IntegrationTest
class PostConcurrentModificationTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		pool = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	private boolean column(long postId, String column) {
		return Boolean.TRUE.equals(
				jdbc.queryForObject("select " + column + " is not null from posts where id = ?", Boolean.class, postId));
	}

	@Test
	@DisplayName("TASK-23#3 TASK-23#B1 관리자 숨김이 먼저 커밋되면 겹친 작성자 수정은 409 conflict 이고 숨김은 유지된다")
	void hideWinsOverEdit() throws Exception {
		User author = fixtures.user();
		User admin = fixtures.admin();
		long postId = board.post(author.getId(), null, "원래 제목");
		Future<HttpResponse<String>> hide;
		Future<HttpResponse<String>> edit;
		try (RowLock lock = RowLock.hold(dataSource, "select id from posts where id = ? for update", postId)) {
			hide = pool.submit(() -> api.post("/admin/posts/" + postId + "/hide").bearer(fixtures.bearer(admin)).send());
			lock.awaitWaiters(1);
			edit = pool.submit(() -> api.patch("/posts/" + postId).bearer(fixtures.bearer(author))
				.json("{\"title\":\"늦은 수정\"}")
				.send());
			lock.awaitWaiters(2);
			lock.commit();
		}
		HttpResponse<String> hidden = hide.get(30, TimeUnit.SECONDS);
		HttpResponse<String> edited = edit.get(30, TimeUnit.SECONDS);
		assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(200);
		assertThat(edited.statusCode()).as(edited.body()).isEqualTo(409);
		assertThat(error(edited)).isEqualTo("conflict");
		assertThat(column(postId, "hidden_at")).as("B1 숨김 유지").isTrue();
		assertThat(jdbc.queryForObject("select title from posts where id = ?", String.class, postId))
			.isEqualTo("원래 제목");
	}

	@Test
	@DisplayName("TASK-23#3 TASK-23#B1 작성자 삭제가 먼저 커밋되면 겹친 관리자 숨김은 409 conflict 이고 삭제는 되살아나지 않는다")
	void deleteWinsOverHide() throws Exception {
		User author = fixtures.user();
		User admin = fixtures.admin();
		long postId = board.post(author.getId(), null, "지울 글");
		Future<HttpResponse<String>> delete;
		Future<HttpResponse<String>> hide;
		try (RowLock lock = RowLock.hold(dataSource, "select id from posts where id = ? for update", postId)) {
			delete = pool.submit(() -> api.delete("/posts/" + postId).bearer(fixtures.bearer(author)).send());
			lock.awaitWaiters(1);
			hide = pool.submit(() -> api.post("/admin/posts/" + postId + "/hide").bearer(fixtures.bearer(admin)).send());
			lock.awaitWaiters(2);
			lock.commit();
		}
		HttpResponse<String> deleted = delete.get(30, TimeUnit.SECONDS);
		HttpResponse<String> hidden = hide.get(30, TimeUnit.SECONDS);
		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
		assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(409);
		assertThat(error(hidden)).isEqualTo("conflict");
		assertThat(column(postId, "deleted_at")).as("B1 삭제 유지").isTrue();
		assertThat(column(postId, "hidden_at")).isFalse();
	}

	@Test
	@DisplayName("TASK-23#3 TASK-23#B1 관리자 숨김이 먼저 커밋되면 겹친 작성자 삭제(커밋 시점에 UPDATE)도 409 conflict, 숨김은 유지되고 삭제는 안 된다")
	void hideWinsOverDelete() throws Exception {
		User author = fixtures.user();
		User admin = fixtures.admin();
		long postId = board.post(author.getId(), null, "숨길 글");
		Future<HttpResponse<String>> hide;
		Future<HttpResponse<String>> delete;
		try (RowLock lock = RowLock.hold(dataSource, "select id from posts where id = ? for update", postId)) {
			hide = pool.submit(() -> api.post("/admin/posts/" + postId + "/hide").bearer(fixtures.bearer(admin)).send());
			lock.awaitWaiters(1);
			delete = pool.submit(() -> api.delete("/posts/" + postId).bearer(fixtures.bearer(author)).send());
			lock.awaitWaiters(2);
			lock.commit();
		}
		HttpResponse<String> hidden = hide.get(30, TimeUnit.SECONDS);
		HttpResponse<String> deleted = delete.get(30, TimeUnit.SECONDS);
		assertThat(hidden.statusCode()).as(hidden.body()).isEqualTo(200);
		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(409);
		assertThat(error(deleted)).isEqualTo("conflict");
		assertThat(column(postId, "hidden_at")).isTrue();
		assertThat(column(postId, "deleted_at")).isFalse();
	}

	@Test
	@DisplayName("TASK-23#3 좋아요·조회수 카운터는 버전을 올리지 않는다(좋아요가 수정을 409 로 만들지 않는다), 순차 수정은 그대로 200")
	void countersDoNotBumpVersion() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "카운터");
		long before = jdbc.queryForObject("select version from posts where id = ?", Long.class, postId);
		assertThat(api.post("/posts/" + postId + "/like").bearer(fixtures.bearer(fixtures.user())).send().statusCode())
			.isEqualTo(200);
		assertThat(api.get("/posts/" + postId).send().statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("select version from posts where id = ?", Long.class, postId)).isEqualTo(before);
		for (String title : new String[] { "하나", "둘" }) {
			assertThat(api.patch("/posts/" + postId).bearer(fixtures.bearer(author))
				.json("{\"title\":\"" + title + "\"}")
				.send()
				.statusCode()).isEqualTo(200);
		}
		assertThat(jdbc.queryForObject("select version from posts where id = ?", Long.class, postId))
			.isEqualTo(before + 2);
	}

}
