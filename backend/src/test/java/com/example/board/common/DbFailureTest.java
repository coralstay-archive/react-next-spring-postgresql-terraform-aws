package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

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
import com.example.board.support.ErrorContractTestController;
import com.example.board.support.IntegrationTest;
import com.example.board.support.RowLock;
import com.example.board.user.User;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.JsonNode;

/**
 * TASK-23#1 (BE-3) DB 장애·락 대기·느린 쿼리의 응답. 타임아웃을 짧게 준 별도 컨텍스트라 PostgreSQL 컨테이너도 이 클래스 전용이다
 * (pause 해도 다른 테스트에 영향이 없다). 타임아웃을 기다릴 뿐 sleep 은 쓰지 않는다.
 */
@IntegrationTest
@TestPropertySource(properties = { "spring.datasource.hikari.connection-timeout=1000",
		"spring.datasource.hikari.validation-timeout=500",
		"spring.datasource.hikari.data-source-properties.socketTimeout=3", "app.db.lock-timeout=300ms",
		"app.db.statement-timeout=1s", "app.db.transaction-timeout=" + DbFailureTest.TRANSACTION_TIMEOUT_SETTING })
class DbFailureTest {

	static final String TRANSACTION_TIMEOUT_SETTING = "2s";

	static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(2);

	/** 부하가 큰 머신에서 transaction_timeout 뒤 응답까지 걸리는 여유(오류 처리·커넥션 정리·HTTP). */
	static final Duration TIMEOUT_SLACK = Duration.ofSeconds(8);

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
		String bearer = fixtures.bearer(author);
		// TASK-35: 잠금을 쥔 쪽은 앱 풀(이 컨텍스트의 transaction_timeout 2s)이 아니라 따로 연 커넥션에 넉넉한 transaction_timeout 을 준다.
		// 부하가 크면 쥔 트랜잭션이 2s 를 넘겨 서버가 세션을 끊고(FATAL: terminating connection due to transaction timeout) 잠금이 풀려
		// 기대한 409 대신 200 이 나왔다.
		try (Connection holder = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword())) {
			try (Statement session = holder.createStatement()) {
				session.execute("set transaction_timeout = '" + RowLock.TRANSACTION_TIMEOUT + "'");
			}
			holder.setAutoCommit(false);
			try (PreparedStatement lock = holder.prepareStatement("select id from posts where id = ? for update")) {
				lock.setLong(1, postId);
				lock.executeQuery().close();
			}
			try {
				error(timed(api.patch("/posts/" + postId).bearer(bearer).json("{\"title\":\"바뀐 제목\"}")), 409,
						"lock_timeout");
			}
			finally {
				holder.rollback();
			}
		}
		assertThat(api.patch("/posts/" + postId).bearer(bearer).json("{\"title\":\"바뀐 제목\"}")
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

	@Test
	@DisplayName("TASK-23#1 TASK-23#B2 문장마다는 statement_timeout 안이어도 합쳐서 transaction_timeout 을 넘는 트랜잭션은 30초 안에 503 db_timeout JSON, 끊긴 커넥션은 풀에서 빠진다")
	void transactionTimeout() throws Exception {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		Instant start = Instant.now();
		JsonNode body = error(timed(api.get("/test/errors/slow-transaction").bearer(bearer)), 503, "db_timeout");
		// 문장을 다 실행하면 SLOW_TRANSACTION(15s)이 걸리는데, transaction_timeout(2s) + 부하 여유로 끝난다 — 30s 예산(B2)이 아니라
		// transaction_timeout 이 끊었다. TASK-35: 부하(load avg 약 29)에서 6.4s 까지 관측돼 4.5s 고정 상한을 설정값 기반 상한으로 바꿨다.
		Duration elapsed = Duration.between(start, Instant.now());
		assertThat(elapsed).isLessThan(TRANSACTION_TIMEOUT.plus(TIMEOUT_SLACK));
		assertThat(TRANSACTION_TIMEOUT.plus(TIMEOUT_SLACK)).isLessThan(ErrorContractTestController.SLOW_TRANSACTION)
			.isLessThan(RESPONSE_BUDGET);
		assertThat(body.toString()).doesNotContain("terminating connection");
		// 서버가 끊은 세션은 풀에 남지 않는다: 바로 풀 크기만큼 빌려도 모두 쓸 수 있다
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		List<Connection> borrowed = new ArrayList<>();
		try {
			for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
				borrowed.add(dataSource.getConnection());
			}
			for (Connection connection : borrowed) {
				try (PreparedStatement statement = connection.prepareStatement("select 1");
						ResultSet result = statement.executeQuery()) {
					assertThat(result.next()).isTrue();
				}
			}
		}
		finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
		}
		assertThat(api.get("/posts").send().statusCode()).isEqualTo(200);
	}

}
