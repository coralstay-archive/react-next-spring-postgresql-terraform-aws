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
 * TASK-23#4 (BE-29) 좋아요의 가시성 확인은 글 행을 잠그고 읽는다(댓글 작성의 FOR SHARE 와 같은 목적. 카운터 UPDATE 와의 교착을 피하려고
 * FOR NO KEY UPDATE — PostLikeService). 확인과 쓰기 사이에 숨김·삭제가 끼어들지 못하고, 글 행이 없으면 500 이 아니라 404 다. 테스트 커넥션이 숨김·삭제를 커밋하지 않고 쥔 채 좋아요를 보내고, 좋아요가 그 행 앞에서
 * 기다리는 것을 확인한 뒤 커밋한다.
 */
@IntegrationTest
class PostLikeVisibilityLockTest {

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
		pool = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private HttpResponse<String> likeDuring(String concurrentSql, long postId) throws Exception {
		User liker = fixtures.user();
		Future<HttpResponse<String>> like;
		try (RowLock lock = RowLock.hold(dataSource, concurrentSql, postId)) {
			like = pool.submit(() -> api.post("/posts/" + postId + "/like").bearer(fixtures.bearer(liker)).send());
			lock.awaitWaiters(1);
			lock.commit();
		}
		return like.get(30, TimeUnit.SECONDS);
	}

	private long likeRows(long postId) {
		return jdbc.queryForObject("select count(*) from post_likes where post_id = ?", Long.class, postId);
	}

	@Test
	@DisplayName("TASK-23#4 좋아요 확인 직후 커밋된 숨김은 좋아요가 보고 404 post_not_found, 좋아요 행은 생기지 않는다")
	void hiddenMeanwhile() throws Exception {
		long postId = board.post(fixtures.user().getId(), null, "숨겨질 글");
		HttpResponse<String> response = likeDuring("update posts set hidden_at = now() where id = ?", postId);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("post_not_found");
		assertThat(likeRows(postId)).isZero();
	}

	@Test
	@DisplayName("TASK-23#4 좋아요 도중 글 행이 사라지면(hard delete) 409·500 이 아니라 404 post_not_found")
	void rowGoneMeanwhile() throws Exception {
		long postId = board.post(fixtures.user().getId(), null, "사라질 글");
		HttpResponse<String> response = likeDuring("delete from posts where id = ?", postId);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("post_not_found");
	}

	@Test
	@DisplayName("TASK-23#4 없는 글의 좋아요·취소는 404 post_not_found")
	void missingPost() {
		User user = fixtures.user();
		for (HttpResponse<String> response : new HttpResponse[] {
				api.post("/posts/987654321/like").bearer(fixtures.bearer(user)).send(),
				api.delete("/posts/987654321/like").bearer(fixtures.bearer(user)).send() }) {
			assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
			assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("post_not_found");
		}
	}

}
