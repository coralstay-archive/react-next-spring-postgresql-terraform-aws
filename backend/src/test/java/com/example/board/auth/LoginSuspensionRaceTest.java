package com.example.board.auth;

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

import com.example.board.admin.AdminUserService;
import com.example.board.auth.token.TokenService;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.RowLock;

/**
 * TASK-24#3 (BE-17) 정지와 동시에 진행된 로그인이 살아 있는 세션·refresh 를 남기지 않는다. sleep 없이 순서를 결정적으로 만든다(RowLock).
 *
 * <ul>
 * <li>JWT: 로그인이 refresh 를 저장하는 트랜잭션에서 사용자 행을 FOR SHARE 로 잠그고 status 를 다시 본다. 테스트 커넥션이 정지
 * 트랜잭션(행 FOR UPDATE + SUSPENDED + refresh 전부 폐기)을 커밋하지 않고 쥔 채 로그인을 보내고, 로그인이 그 행 앞에서 기다리는 것을
 * 확인한 뒤 커밋한다.</li>
 * <li>세션: 세션 행은 요청 끝에 Spring Session 이 따로 저장하므로, 저장 뒤 status 를 다시 본다. 테스트 커넥션이 spring_session 을 잠가
 * 로그인의 세션 저장을 붙잡은 사이 실제 정지(AdminUserService.suspend — 커밋 후 세션 삭제까지)를 끝내고 잠금을 푼다.</li>
 * </ul>
 */
@IntegrationTest
@TestPropertySource(properties = { "app.db.lock-timeout=" + RowLock.LOCK_TIMEOUT, "app.db.statement-timeout=" + RowLock.LOCK_TIMEOUT,
		"app.db.transaction-timeout=" + RowLock.TRANSACTION_TIMEOUT,
		"spring.datasource.hikari.data-source-properties.socketTimeout=" + RowLock.SOCKET_TIMEOUT_SECONDS,
		// 만료 세션 정리 크론(매분 0초, DELETE FROM SPRING_SESSION ...)이 spring_session 잠금 대기자로 잡히지 않게 끈다.
		"spring.session.jdbc.cleanup-cron=-" })
class LoginSuspensionRaceTest {

	@LocalServerPort
	int port;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	AdminUserService adminUsers;

	Api api;

	AuthClient auth;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		pool = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	private long userId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email.toLowerCase());
	}

	private int liveRefreshTokens(long userId) {
		return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
				Integer.class, userId);
	}

	private int sessionsOf(long userId) {
		return jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class,
				String.valueOf(userId));
	}

	@Test
	@DisplayName("TASK-24#3 JWT 로그인 도중 정지가 커밋되면 로그인은 403 suspended 이고 살아 있는 refresh 가 남지 않는다")
	void jwtLoginRacingSuspension() throws Exception {
		String email = auth.signedUp();
		long id = userId(email);
		Future<HttpResponse<String>> login;
		try (RowLock suspension = RowLock.hold(dataSource, "select id from users where id = ? for update", id)) {
			suspension.execute("update users set status = 'SUSPENDED' where id = ?", id)
				.execute("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null", id);
			login = pool.submit(() -> auth.jwtLogin(email, AuthClient.PASSWORD));
			suspension.awaitWaiters(1, "users|refresh_tokens");
			suspension.commit();
		}
		HttpResponse<String> response = login.get(30, TimeUnit.SECONDS);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("suspended");
		assertThat(liveRefreshTokens(id)).isZero();
	}

	@Test
	@DisplayName("TASK-24#3 세션 로그인의 세션 저장 직전에 정지가 끝나도 저장 뒤 재확인이 그 세션을 지운다(다음 요청 401)")
	void sessionLoginRacingSuspension() throws Exception {
		String email = auth.signedUp();
		long id = userId(email);
		AuthUser admin = AuthUser.from(fixtures.admin());
		String csrf = auth.csrfToken();
		Future<HttpResponse<String>> login;
		try (RowLock sessionTable = RowLock.hold(dataSource, "lock table spring_session in share mode")) {
			login = pool.submit(() -> auth.withIp(api.post("/auth/login"))
				.csrf(csrf)
				.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
				.send());
			// 로그인 요청의 세션 저장(INSERT INTO SPRING_SESSION ...)만 센다.
			sessionTable.awaitWaiters(1, "insert\\s+into\\s+spring_session");
			// 로그인은 status 확인을 마치고 세션 저장에서 기다린다. 그 사이 정지(커밋 → 세션 삭제)가 끝까지 실행된다.
			adminUsers.suspend(admin, id, "TASK-24#3 race");
		}
		HttpResponse<String> response = login.get(30, TimeUnit.SECONDS);
		String session = "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();

		assertThat(sessionsOf(id)).isZero();
		assertThat(api.get("/auth/me").cookie(session).send().statusCode()).isEqualTo(401);
	}


	@Test
	@DisplayName("TASK-24#3 refresh 로테이션 도중 정지가 끼어들어도 살아 있는 refresh 가 남지 않는다(로테이션도 사용자 행 FOR SHARE 를 먼저 잡는다)")
	void refreshRotationRacingSuspension() throws Exception {
		String email = auth.signedUp();
		long id = userId(email);
		String refreshToken = auth.tokens(email).get("refreshToken").asString();
		ExecutorService two = Executors.newFixedThreadPool(2);
		Future<HttpResponse<String>> rotation;
		try (RowLock suspension = RowLock.hold(dataSource, "select id from users where id = ? for no key update", id)) {
			// 정지 트랜잭션(AdminUserService.suspend: 사용자 행 PESSIMISTIC_WRITE = FOR NO KEY UPDATE → status 변경 → refresh 전부 폐기)을
			// 커밋하지 않고 쥔다.
			suspension.execute("update users set status = 'SUSPENDED' where id = ?", id);
			Future<?> revokeAll;
			try (RowLock tokenRow = RowLock.hold(dataSource,
					"select id from refresh_tokens where token_hash = ? for update", TokenService.hash(refreshToken))) {
				// 로테이션을 먼저 출발시키고(고치기 전: 토큰 행 앞에서, 고친 뒤: 사용자 행 앞에서 기다린다),
				rotation = two.submit(() -> auth.refresh(refreshToken));
				suspension.awaitWaiters(1, "users|refresh_tokens");
				// 정지의 "refresh 전부 폐기" 문장이 로테이션의 새 토큰 커밋보다 먼저 시작되게 한다(토큰 행 앞에서 기다린다).
				revokeAll = two.submit(() -> suspension
					.execute("update refresh_tokens set revoked_at = now() where user_id = ? and revoked_at is null", id));
				suspension.awaitWaiters(2, "users|refresh_tokens");
			}
			revokeAll.get(30, TimeUnit.SECONDS);
			suspension.commit();
		}
		finally {
			two.shutdown();
		}
		HttpResponse<String> response = rotation.get(30, TimeUnit.SECONDS);

		assertThat(liveRefreshTokens(id)).as(response.body()).isZero();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("invalid_refresh_token");
	}

}
