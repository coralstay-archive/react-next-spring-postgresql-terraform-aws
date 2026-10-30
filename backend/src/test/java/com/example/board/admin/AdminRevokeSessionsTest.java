package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.FailingDelete;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/**
 * TASK-33#5 정지 경고(session_revoke_failed)의 '세션 끊기 다시 시도'용 세션 삭제 전용 API
 * {@code POST /admin/users/{id}/sessions/revoke}. 정지 상태는 건드리지 않고 그 사용자의 refresh 토큰을 모두 폐기하고 Spring Session
 * 을 모두 지운다(정지의 세션·토큰 뒤처리와 같다). 멱등, 감사 로그 USER_SESSIONS_REVOKE, 실패하면 200 + warnings.
 */
@IntegrationTest
class AdminRevokeSessionsTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	User admin;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		admin = fixtures.admin();
		adminBearer = fixtures.bearer(admin);
	}

	private long sessions(long userId) {
		return jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				String.valueOf(userId));
	}

	private long liveRefreshTokens(long userId) {
		return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
				Long.class, userId);
	}

	private String status(long userId) {
		return jdbc.queryForObject("select status from users where id = ?", String.class, userId);
	}

	private List<Map<String, Object>> auditRows(long userId) {
		return jdbc.queryForList(
				"select admin_id, action, target_type, reason from admin_actions where action = 'USER_SESSIONS_REVOKE' and target_id = ? order by id",
				userId);
	}

	private HttpResponse<String> revoke(long userId) {
		return api.post("/admin/users/" + userId + "/sessions/revoke").bearer(adminBearer).send();
	}

	private long signedUpUser(String email) {
		String session = auth.sessionCookie(email);
		return AuthClient.json(api.get("/auth/me").cookie(session).send()).get("id").asLong();
	}

	@Test
	@DisplayName("TASK-33#5 활성 사용자: 세션·refresh 토큰을 모두 끊고 상태는 ACTIVE 그대로, 200 {warnings: []}, 감사 로그, 멱등")
	void revokesActiveUserSessions() {
		String email = auth.signedUp();
		long userId = signedUpUser(email);
		auth.tokens(email);
		assertThat(sessions(userId)).isEqualTo(1);
		assertThat(liveRefreshTokens(userId)).isPositive();

		HttpResponse<String> response = api.post("/admin/users/" + userId + "/sessions/revoke")
			.bearer(adminBearer)
			.json("{\"reason\":\"세션 정리\"}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("warnings").toString()).isEqualTo("[]");
		assertThat(sessions(userId)).isZero();
		assertThat(liveRefreshTokens(userId)).isZero();
		assertThat(status(userId)).isEqualTo("ACTIVE");
		assertThat(auditRows(userId)).singleElement().satisfies(row -> {
			assertThat(((Number) row.get("admin_id")).longValue()).isEqualTo(admin.getId());
			assertThat(row.get("target_type")).isEqualTo("USER");
			assertThat(row.get("reason")).isEqualTo("세션 정리");
		});

		HttpResponse<String> again = revoke(userId);
		assertThat(again.statusCode()).as("멱등").isEqualTo(200);
		assertThat(AuthClient.json(again).get("warnings").toString()).isEqualTo("[]");
		assertThat(auditRows(userId)).hasSize(2);
	}

	@Test
	@DisplayName("TASK-33#5 정지 뒤 세션 삭제가 실패했던 사용자: 다시 시도하면 남은 세션을 지우고 정지는 그대로")
	void retryAfterSuspendFailure() {
		String email = auth.signedUp();
		long userId = signedUpUser(email);
		try (FailingDelete failing = FailingDelete.on(jdbc, "spring_session", "principal_name", String.valueOf(userId))) {
			HttpResponse<String> suspended = api.post("/admin/users/" + userId + "/suspend").bearer(adminBearer).send();
			assertThat(AuthClient.json(suspended).get("warnings").toString()).isEqualTo("[\"session_revoke_failed\"]");
		}
		assertThat(sessions(userId)).isEqualTo(1);

		HttpResponse<String> response = revoke(userId);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("warnings").toString()).isEqualTo("[]");
		assertThat(sessions(userId)).isZero();
		assertThat(status(userId)).isEqualTo("SUSPENDED");
	}

	@Test
	@DisplayName("TASK-33#5 세션 삭제가 또 실패하면 200 + warnings [session_revoke_failed], 토큰 폐기·감사 로그는 커밋")
	void revokeFailureWarns() {
		String email = auth.signedUp();
		long userId = signedUpUser(email);
		auth.tokens(email);

		HttpResponse<String> response;
		try (FailingDelete failing = FailingDelete.on(jdbc, "spring_session", "principal_name", String.valueOf(userId))) {
			response = revoke(userId);
		}

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("warnings").toString()).isEqualTo("[\"session_revoke_failed\"]");
		assertThat(sessions(userId)).isEqualTo(1);
		assertThat(liveRefreshTokens(userId)).isZero();
		assertThat(auditRows(userId)).hasSize(1);
	}

	@Test
	@DisplayName("TASK-33#5 없는 사용자 404 user_not_found, 자기 자신·다른 ADMIN 은 409")
	void notFoundAndGuards() {
		HttpResponse<String> missing = revoke(Long.MAX_VALUE);
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(AuthClient.json(missing).get("error").asString()).isEqualTo("user_not_found");

		HttpResponse<String> self = revoke(admin.getId());
		assertThat(self.statusCode()).isEqualTo(409);
		assertThat(AuthClient.json(self).get("error").asString()).isEqualTo("cannot_revoke_self");

		User otherAdmin = fixtures.admin();
		HttpResponse<String> other = revoke(otherAdmin.getId());
		assertThat(other.statusCode()).isEqualTo(409);
		assertThat(AuthClient.json(other).get("error").asString()).isEqualTo("cannot_revoke_admin");
		assertThat(auditRows(admin.getId())).isEmpty();
		assertThat(auditRows(otherAdmin.getId())).isEmpty();
	}

}
