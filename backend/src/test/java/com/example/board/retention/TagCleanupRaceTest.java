package com.example.board.retention;

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
import org.springframework.test.context.TestPropertySource;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.RowLock;

/**
 * TASK-27#1 미사용 태그 정리와 같은 태그를 쓰는 글 저장의 경쟁. 테스트 커넥션이 한쪽의 잠금을 커밋 전 상태로 쥐고, 다른 쪽이 그 잠금 앞에서
 * 기다리거나(pg_stat_activity 로 확인) 건너뛰는 것을 결정적으로 본다.
 *
 * <ul>
 * <li>글 저장이 먼저 태그를 잡으면(TagService.resolve 의 FOR KEY SHARE) 정리는 그 태그를 건너뛴다(SKIP LOCKED) — 저장이 커밋하면 쓰이는
 * 태그다.</li>
 * <li>정리가 먼저 태그를 잡고 지우면 글 저장은 기다렸다가 태그를 다시 만들어 붙인다 — 500 이나 태그 없는 글이 되지 않는다.</li>
 * </ul>
 */
@IntegrationTest
@TestPropertySource(properties = { "app.db.lock-timeout=" + RowLock.LOCK_TIMEOUT, "app.db.statement-timeout=" + RowLock.LOCK_TIMEOUT,
		"app.db.transaction-timeout=" + RowLock.TRANSACTION_TIMEOUT,
		"spring.datasource.hikari.data-source-properties.socketTimeout=" + RowLock.SOCKET_TIMEOUT_SECONDS })
class TagCleanupRaceTest {

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

	@Autowired
	RetentionCleanup cleanup;

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

	private boolean tagExists(String name) {
		return jdbc.queryForObject("select count(*) from tags where name = ?", Integer.class, name) == 1;
	}

	@Test
	@DisplayName("TASK-27#1 TASK-27#B1 글 저장이 태그를 잡고 있으면(FOR KEY SHARE, 커밋 전) 정리는 기다리지 않고 그 태그를 건너뛴다 → 저장 커밋 뒤 태그가 남는다")
	void cleanupSkipsTagHeldBySave() throws Exception {
		String name = BoardFixtures.unique("race-a");
		jdbc.update("insert into tags (name) values (?)", name);
		long post = board.post(fixtures.user().getId(), null, "경쟁");

		try (RowLock save = RowLock.hold(dataSource, "select id from tags where name = ? for key share", name)) {
			Future<RetentionCleanup.Result> swept = pool.submit(cleanup::cleanUp);
			swept.get(20, TimeUnit.SECONDS);
			assertThat(tagExists(name)).as("잡힌 태그는 건너뛴다").isTrue();
			save.execute("insert into post_tags (post_id, tag_id) select ?, id from tags where name = ?", post, name);
			save.commit();
		}

		cleanup.cleanUp();
		assertThat(tagExists(name)).as("커밋 뒤에는 쓰이는 태그").isTrue();
	}

	@Test
	@DisplayName("TASK-27#1 정리가 태그를 먼저 잡고(FOR UPDATE) 지우면 같은 태그로 글을 쓰는 요청은 기다렸다가 태그를 다시 만들어 붙인다(201)")
	void saveRecreatesTagDeletedByCleanup() throws Exception {
		String name = BoardFixtures.unique("race-b");
		jdbc.update("insert into tags (name) values (?)", name);
		String bearer = fixtures.bearer(fixtures.user());

		Future<HttpResponse<String>> created;
		try (RowLock sweep = RowLock.hold(dataSource, "select id from tags where name = ? for update", name)) {
			created = pool.submit(() -> api.post("/posts")
				.bearer(bearer)
				.json("{\"title\":\"경쟁\",\"content\":\"본문\",\"tags\":[\"" + name + "\"]}")
				.send());
			sweep.awaitWaiters(1, "tags");
			sweep.execute("delete from tags where name = ?", name);
			sweep.commit();
		}

		HttpResponse<String> response = created.get(30, TimeUnit.SECONDS);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		assertThat(AuthClient.json(response).get("tags").toString()).contains(name);
		assertThat(tagExists(name)).isTrue();
	}

}
