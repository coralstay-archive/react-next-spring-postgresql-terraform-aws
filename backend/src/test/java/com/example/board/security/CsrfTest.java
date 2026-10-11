package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;

/** TASK-2#3 CSRF: CookieCsrfTokenRepository(XSRF-TOKEN 쿠키, X-XSRF-TOKEN 헤더), Bearer 요청만 면제. */
@IntegrationTest
class CsrfTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private String csrfToken() {
		HttpResponse<String> response = api.get("/auth/csrf").send();
		assertThat(response.statusCode()).isEqualTo(200);
		return Api.cookie(response, "XSRF-TOKEN").orElseThrow();
	}

	@Test
	@DisplayName("TASK-2#3 GET /api/auth/csrf 는 인증 없이 JS 가 읽을 수 있는 XSRF-TOKEN 쿠키(path=/)를 내려준다")
	void csrfBootstrap() {
		HttpResponse<String> response = api.get("/auth/csrf").send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("X-XSRF-TOKEN");
		assertThat(Api.setCookie(response, "XSRF-TOKEN")).hasValueSatisfying(cookie -> assertThat(cookie)
			.doesNotContainIgnoringCase("HttpOnly")
			.containsIgnoringCase("Path=/;")
			.containsIgnoringCase("SameSite=Lax"));
		assertThat(Api.cookie(response, "XSRF-TOKEN")).hasValueSatisfying(token -> assertThat(token).isNotBlank());
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B1 세션 요청의 POST 는 CSRF 토큰이 없으면 403")
	void sessionPostWithoutCsrfIs403() {
		HttpResponse<String> response = api.post("/test/resources").cookie(fixtures.sessionCookie(fixtures.user())).send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B1 세션 요청의 PUT·PATCH·DELETE 는 CSRF 토큰이 없으면 403")
	void sessionPatchDeleteWithoutCsrfIs403() {
		String session = fixtures.sessionCookie(fixtures.user());

		for (String method : new String[] { "PUT", "PATCH", "DELETE" }) {
			assertThat(api.method(method, "/test/echo").cookie(session).send().statusCode()).as(method).isEqualTo(403);
		}
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B1 쿠키와 헤더의 CSRF 토큰이 다르면 403")
	void mismatchedCsrfIs403() {
		String session = fixtures.sessionCookie(fixtures.user());
		String token = csrfToken();

		HttpResponse<String> response = api.post("/test/resources")
			.cookie(session)
			.cookie("XSRF-TOKEN", token)
			.header("X-XSRF-TOKEN", token + "x")
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B1 세션 요청에 유효한 CSRF 토큰(쿠키+X-XSRF-TOKEN 헤더)이 있으면 통과한다")
	void sessionWriteWithCsrfPasses() {
		String session = fixtures.sessionCookie(fixtures.user());
		String token = csrfToken();

		assertThat(api.post("/test/resources").cookie(session).csrf(token).send().statusCode()).isEqualTo(201);
		for (String method : new String[] { "PUT", "PATCH", "DELETE" }) {
			assertThat(api.method(method, "/test/echo").cookie(session).csrf(token).send().statusCode()).as(method)
				.isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B2 Bearer 요청은 CSRF 토큰 없이 POST·PUT·PATCH·DELETE 가 된다")
	void bearerIsCsrfExempt() {
		String bearer = fixtures.bearer(fixtures.user());

		assertThat(api.post("/test/resources").bearer(bearer).send().statusCode()).isEqualTo(201);
		for (String method : new String[] { "PUT", "PATCH", "DELETE" }) {
			assertThat(api.method(method, "/test/echo").bearer(bearer).send().statusCode()).as(method).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-2#3 TASK-2#B2 잘못된 Bearer 로 CSRF 를 우회할 수 없다: 세션 쿠키가 있어도 401")
	void invalidBearerCannotBypassCsrf() {
		String session = fixtures.sessionCookie(fixtures.user());

		HttpResponse<String> response = api.post("/test/resources").cookie(session).bearer("garbage").send();

		assertThat(response.statusCode()).isEqualTo(401);
	}

}
