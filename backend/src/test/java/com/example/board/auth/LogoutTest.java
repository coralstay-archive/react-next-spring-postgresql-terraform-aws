package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.auth.token.TokenService;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** TASK-3#3 POST /api/auth/logout: 세션이면 무효화, Bearer 면 refresh family 폐기. */
@IntegrationTest
class LogoutTest {

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

	private void suspend(String email) {
		jdbc.update("""
				update users set status = 'SUSPENDED'
				where id = (select user_id from user_identities where provider = 'LOCAL' and subject = ?)""", email);
	}

	private int liveTokensInFamilyOf(String rawRefreshToken) {
		return jdbc.queryForObject("""
				select count(*) from refresh_tokens where revoked_at is null
				and family_id = (select family_id from refresh_tokens where token_hash = ?)""", Integer.class,
				TokenService.hash(rawRefreshToken));
	}

	private HttpResponse<String> sessionLogout(String session) {
		return api.post("/auth/logout").cookie(session).csrf(auth.csrfToken()).send();
	}

	@Test
	@DisplayName("TASK-3#3 세션 로그아웃: 204, 세션 삭제 + SESSION 쿠키 만료, 이전 쿠키로는 더 이상 인증되지 않는다")
	void sessionLogout() {
		String session = auth.sessionCookie(auth.signedUp());
		String sessionId = new String(Base64.getDecoder().decode(session.substring("SESSION=".length())));
		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(200);

		HttpResponse<String> response = sessionLogout(session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(Api.setCookie(response, "SESSION"))
			.hasValueSatisfying(cookie -> assertThat(cookie).startsWith("SESSION=;").containsIgnoringCase("Max-Age=0"));
		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class,
				sessionId))
			.isZero();
	}

	@Test
	@DisplayName("TASK-3#3 세션 로그아웃은 CSRF 토큰이 없으면 403 이고 세션은 그대로다")
	void sessionLogoutRequiresCsrf() {
		String session = auth.sessionCookie(auth.signedUp());

		HttpResponse<String> response = api.post("/auth/logout").cookie(session).send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-3#3 로그인하지 않은 상태의 로그아웃도 204 (멱등)")
	void anonymousLogoutIsNoop() {
		HttpResponse<String> response = api.post("/auth/logout").csrf(auth.csrfToken()).send();

		assertThat(response.statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-3#3 Bearer 로그아웃 + refreshToken: 204, 그 토큰의 family 전체를 폐기한다 (CSRF 불필요)")
	void bearerLogoutRevokesFamily() {
		JsonNode tokens = auth.tokens(auth.signedUp());
		String refresh = tokens.get("refreshToken").asString();
		assertThat(liveTokensInFamilyOf(refresh)).isEqualTo(1);

		HttpResponse<String> response = api.post("/auth/logout")
			.bearer(tokens.get("accessToken").asString())
			.json("{\"refreshToken\":" + AuthClient.quote(refresh) + "}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(refresh)).isZero();
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
	}

	@Test
	@DisplayName("TASK-3#3 Bearer 로그아웃은 본문이 없어도 204")
	void bearerLogoutWithoutBody() {
		JsonNode tokens = auth.tokens(auth.signedUp());

		HttpResponse<String> response = api.post("/auth/logout").bearer(tokens.get("accessToken").asString()).send();

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(tokens.get("refreshToken").asString())).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-3#3 남의 refresh 토큰으로는 그 family 를 폐기하지 않는다(204 는 같다)")
	void bearerLogoutIgnoresOthersToken() {
		JsonNode mine = auth.tokens(auth.signedUp());
		String othersRefresh = auth.tokens(auth.signedUp()).get("refreshToken").asString();

		HttpResponse<String> response = api.post("/auth/logout")
			.bearer(mine.get("accessToken").asString())
			.json("{\"refreshToken\":" + AuthClient.quote(othersRefresh) + "}")
			.send();

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(othersRefresh)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-3#3 정지(SUSPENDED)된 사용자도 로그아웃할 수 있다(세션·Bearer), 다른 쓰기는 여전히 403")
	void suspendedCanLogOut() {
		String sessionEmail = auth.signedUp();
		String session = auth.sessionCookie(sessionEmail);
		String bearerEmail = auth.signedUp();
		JsonNode tokens = auth.tokens(bearerEmail);
		suspend(sessionEmail);
		suspend(bearerEmail);
		assertThat(api.post("/test/echo").cookie(session).csrf(auth.csrfToken()).send().statusCode()).isEqualTo(403);

		HttpResponse<String> sessionResponse = sessionLogout(session);
		HttpResponse<String> bearerResponse = api.post("/auth/logout")
			.bearer(tokens.get("accessToken").asString())
			.json("{\"refreshToken\":" + AuthClient.quote(tokens.get("refreshToken").asString()) + "}")
			.send();

		assertThat(sessionResponse.statusCode()).as(sessionResponse.body()).isEqualTo(204);
		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(401);
		assertThat(bearerResponse.statusCode()).as(bearerResponse.body()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(tokens.get("refreshToken").asString())).isZero();
	}

}
