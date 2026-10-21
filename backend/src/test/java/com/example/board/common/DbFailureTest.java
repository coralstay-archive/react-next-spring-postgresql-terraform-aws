package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/**
 * TASK-23#1 (BE-3) DB 장애·락 대기·느린 쿼리의 응답. 타임아웃을 짧게 준 별도 컨텍스트라 PostgreSQL 컨테이너도 이 클래스 전용이다
 * (pause 해도 다른 테스트에 영향이 없다). 타임아웃을 기다릴 뿐 sleep 은 쓰지 않는다.
 */
@IntegrationTest
@TestPropertySource(properties = { "spring.datasource.hikari.connection-timeout=1000",
		"spring.datasource.hikari.validation-timeout=500",
		"spring.datasource.hikari.data-source-properties.socketTimeout=3", "app.db.lock-timeout=300ms",
		"app.db.statement-timeout=1s" })
class DbFailureTest {

	/** B2: DB 장애 중에도 응답은 이 시간 안에 끝난다. */
	static final Duration RESPONSE_BUDGET = Duration.ofSeconds(30);

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	DataSource dataSource;

	@Autowired
	PostgreSQLContainer postgres;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private static JsonNode error(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("error").asString()).isEqualTo(code);
		assertThat(body.get("message").asString()).isNotBlank();
		return body;
	}

	private static HttpResponse<String> timed(Api.Request request) {
		Instant start = Instant.now();
		HttpResponse<String> response = request.send();
		assertThat(Duration.between(start, Instant.now())).as("TASK-23#B2 응답 시간").isLessThan(RESPONSE_BUDGET);
		return response;
	}

	@Test
	@DisplayName("TASK-23#1 TASK-23#B2 DB 가 응답하지 않으면(컨테이너 pause) 익명 읽기·세션 요청·Bearer 쓰기 모두 30초 안에 503 db_unavailable JSON")
	void databaseDown() {
		User user = fixtures.user();
		String session = fixtures.sessionCookie(user);
		String bearer = fixtures.bearer(user);
		var docker = postgres.getDockerClient();
		docker.pauseContainerCmd(postgres.getContainerId()).exec();
		try {
			error(timed(api.get("/posts")), 503, "db_unavailable");
			// 세션 조회는 MVC 밖(Spring Session 필터)에서 일어난다 → /error 경로도 같은 매핑
			error(timed(api.get("/auth/me").cookie(session)), 503, "db_unavailable");
			// 정지 계정 확인(보안 필터)도 DB 를 본다
			error(timed(api.post("/posts").bearer(bearer).json("{\"title\":\"t\",\"content\":\"c\"}")), 503,
					"db_unavailable");
		}
		finally {
			docker.unpauseContainerCmd(postgres.getContainerId()).exec();
		}
		// 복구되면 다시 정상
		assertThat(api.get("/posts").send().statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-23#1 다른 트랜잭션이 글 행을 잠그고 있으면 lock_timeout 뒤 409 lock_timeout (무한 대기 없음)")
	void lockTimeout() throws Exception {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "잠긴 글");
		try (Connection holder = dataSource.getConnection()) {
			holder.setAutoCommit(false);
			try (PreparedStatement lock = holder.prepareStatement("select id from posts where id = ? for update")) {
				lock.setLong(1, postId);
				lock.executeQuery().close();
			}
			try {
				error(timed(api.patch("/posts/" + postId).bearer(fixtures.bearer(author))
					.json("{\"title\":\"바뀐 제목\"}")), 409, "lock_timeout");
			}
			finally {
				holder.rollback();
			}
		}
		assertThat(api.patch("/posts/" + postId).bearer(fixtures.bearer(author)).json("{\"title\":\"바뀐 제목\"}")
			.send()
			.statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-23#1 statement_timeout 을 넘긴 쿼리는 503 db_timeout (내부 메시지 없음)")
	void statementTimeout() {
		User user = fixtures.user();
		JsonNode body = error(timed(api.get("/test/errors/slow-query").bearer(fixtures.bearer(user))), 503,
				"db_timeout");
		assertThat(body.toString()).doesNotContain("canceling statement");
	}

}
