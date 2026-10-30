package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.FailingDelete;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * TASK-27#3 BE-38 정지는 커밋한 뒤 그 사용자의 세션을 지운다. 세션 삭제가 실패해도 정지는 그대로(커밋됨)이고 관리자에게는 200 + 경고
 * {@code warnings: ["session_revoke_failed"]} 를 준다(500 이 아니다). 실패는 그 사용자의 SPRING_SESSION DELETE 만 막는 임시 트리거로
 * 만든다. 남은 세션은 요청마다 DB status 를 보는 필터(TASK-24) 때문에 쓰기를 못 한다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AdminSuspendSessionFailureTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		adminBearer = fixtures.bearer(fixtures.admin());
	}

	private long sessions(long userId) {
		return jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				String.valueOf(userId));
	}

	@Test
	@DisplayName("TASK-27#3 정지 뒤 세션 삭제가 실패하면 200 + warnings [session_revoke_failed], 정지·refresh 폐기는 커밋된 채, WARN 로그")
	void sessionRevokeFailure(CapturedOutput output) {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		JsonNode tokens = auth.tokens(email);
		long userId = AuthClient.json(api.get("/auth/me").cookie(session).send()).get("id").asLong();

		HttpResponse<String> response;
		try (FailingDelete failing = FailingDelete.on(jdbc, "spring_session", "principal_name", String.valueOf(userId))) {
			response = api.post("/admin/users/" + userId + "/suspend").bearer(adminBearer).send();
		}

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("id").asLong()).isEqualTo(userId);
		assertThat(body.get("status").asString()).isEqualTo("SUSPENDED");
		assertThat(body.get("warnings").toString()).isEqualTo("[\"session_revoke_failed\"]");
		assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, userId))
			.isEqualTo("SUSPENDED");
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
				Long.class, userId)).isZero();
		assertThat(sessions(userId)).as("세션은 남았다").isEqualTo(1);
		assertThat(output.getOut()).contains("정지 사용자 세션 삭제 실패").contains("user=" + userId)
			.doesNotContain(email);

		HttpResponse<String> write = api.post("/posts")
			.cookie(session)
			.csrf(auth.csrfToken())
			.json("{\"title\":\"t\",\"content\":\"c\"}")
			.send();
		assertThat(write.statusCode()).as("남은 세션으로는 쓰지 못한다(TASK-24)").isIn(401, 403);
	}

	@Test
	@DisplayName("TASK-27#3 세션 삭제가 성공하면 warnings 는 빈 배열")
	void noWarnings() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		long userId = AuthClient.json(api.get("/auth/me").cookie(session).send()).get("id").asLong();

		HttpResponse<String> response = api.post("/admin/users/" + userId + "/suspend").bearer(adminBearer).send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("warnings").toString()).isEqualTo("[]");
		assertThat(sessions(userId)).isZero();
	}

}
