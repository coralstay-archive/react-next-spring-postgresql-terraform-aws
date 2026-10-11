package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** TASK-3#4 GET /api/auth/me: 세션·Bearer 모두 현재 사용자, 익명은 401. */
@IntegrationTest
class MeTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private long userId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	private static void assertMe(HttpResponse<String> response, long id, String email, String status) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("nickname").asString()).isEqualTo("tester");
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.get("role").asString()).isEqualTo("USER");
		assertThat(body.get("status").asString()).isEqualTo(status);
		assertThat(body.get("providers").isArray()).isTrue();
	}

	@Test
	@DisplayName("TASK-3#4 세션으로 GET /auth/me → id·nickname·email·role·status·providers")
	void sessionMe() {
		String email = auth.signedUp();

		HttpResponse<String> response = api.get("/auth/me").cookie(auth.sessionCookie(email)).send();

		assertMe(response, userId(email), email, "ACTIVE");
		assertThat(AuthClient.json(response).get("providers").toString()).isEqualTo("[\"LOCAL\"]");
	}

	@Test
	@DisplayName("TASK-3#4 Bearer 로 GET /auth/me → 같은 응답")
	void bearerMe() {
		String email = auth.signedUp();

		HttpResponse<String> response = api.get("/auth/me")
			.bearer(auth.tokens(email).get("accessToken").asString())
			.send();

		assertMe(response, userId(email), email, "ACTIVE");
	}

	@Test
	@DisplayName("TASK-3#4 익명이면 401 JSON")
	void anonymousIs401() {
		HttpResponse<String> response = api.get("/auth/me").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"error\":\"unauthorized\"");
	}

	@Test
	@DisplayName("TASK-3#4 응답은 DB 의 최신 값이다: 정지되면 status=SUSPENDED, 연결된 수단이 늘면 providers 에 보인다")
	void reflectsCurrentState() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		long id = userId(email);
		jdbc.update("update users set status = 'SUSPENDED' where id = ?", id);
		jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'GOOGLE', ?)", id,
				"google-" + id);

		HttpResponse<String> response = api.get("/auth/me").cookie(session).send();

		assertMe(response, id, email, "SUSPENDED");
		List<String> providers = AuthClient.json(response).get("providers").valueStream().map(JsonNode::asString).toList();
		assertThat(providers).containsExactly("LOCAL", "GOOGLE");
	}

	@Test
	@DisplayName("TASK-3#4 계정이 지워졌으면 세션이 남아 있어도 401")
	void deletedUserIs401() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		jdbc.update("delete from users where id = ?", userId(email));

		assertThat(api.get("/auth/me").cookie(session).send().statusCode()).isEqualTo(401);
	}

}
