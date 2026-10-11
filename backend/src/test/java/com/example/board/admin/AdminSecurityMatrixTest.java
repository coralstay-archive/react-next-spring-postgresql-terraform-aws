package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/**
 * TASK-12#1 B1: {@code /api/admin/**} 는 보안 체인에서 ADMIN 만 통과한다. 익명은 401, 비ADMIN(세션·Bearer, 정지 포함)은 403.
 * 컨트롤러가 있든 없든(없는 id 여도) 체인에서 먼저 막히므로 대상 id 는 아무 값이나 쓴다. 쓰기 요청은 CSRF 를 붙여서 CSRF 가 아니라
 * 인가 규칙 때문에 막히는지 본다.
 */
@IntegrationTest
@DisplayName("TASK-12#1 TASK-12#B1 관리자 API 권한 매트릭스")
class AdminSecurityMatrixTest {

	enum Actor {
		ANONYMOUS, USER_SESSION, USER_BEARER, SUSPENDED_BEARER
	}

	static final String[][] ENDPOINTS = { { "GET", "/admin/users" }, { "POST", "/admin/users/1/suspend" },
			{ "POST", "/admin/users/1/unsuspend" }, { "POST", "/admin/posts/1/hide" },
			{ "POST", "/admin/posts/1/unhide" }, { "POST", "/admin/posts/1/restore" },
			{ "POST", "/admin/categories" }, { "PATCH", "/admin/categories/1" }, { "DELETE", "/admin/categories/1" },
			{ "GET", "/admin/actions" } };

	static Stream<Arguments> matrix() {
		return Arrays.stream(ENDPOINTS)
			.flatMap(e -> Arrays.stream(Actor.values())
				.map(a -> Arguments.of(a, e[0], e[1], a == Actor.ANONYMOUS ? 401 : 403)));
	}

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	@ParameterizedTest(name = "TASK-12#B1 {0} {1} {2} → {3}")
	@MethodSource("matrix")
	@DisplayName("TASK-12#B1 비ADMIN 403, 익명 401")
	void nonAdminIsRejected(Actor actor, String method, String path, int expected) {
		Api.Request request = api.method(method, path).csrf(auth.csrfToken());
		if (!method.equals("GET")) {
			request.json("{}");
		}
		switch (actor) {
			case ANONYMOUS -> {
			}
			case USER_SESSION -> request.cookie(fixtures.sessionCookie(fixtures.user()));
			case USER_BEARER -> request.bearer(fixtures.bearer(fixtures.user()));
			case SUSPENDED_BEARER -> request.bearer(fixtures.bearer(fixtures.suspended()));
		}
		HttpResponse<String> response = request.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
	}

	@Test
	@DisplayName("TASK-12#B1 ADMIN 은 체인을 통과한다(목록 200). 세션 ADMIN 의 쓰기도 CSRF 는 그대로 검사한다")
	void adminPassesButCsrfStillApplies() {
		User admin = fixtures.admin();
		assertThat(api.get("/admin/users").bearer(fixtures.bearer(admin)).send().statusCode()).isEqualTo(200);
		String session = fixtures.sessionCookie(admin);
		assertThat(api.get("/admin/users").cookie(session).send().statusCode()).isEqualTo(200);

		User target = fixtures.user();
		HttpResponse<String> noCsrf = api.post("/admin/users/" + target.getId() + "/suspend").cookie(session).send();
		assertThat(noCsrf.statusCode()).isEqualTo(403);
		HttpResponse<String> withCsrf = api.post("/admin/users/" + target.getId() + "/suspend")
			.cookie(session)
			.csrf(auth.csrfToken())
			.send();
		assertThat(withCsrf.statusCode()).as(withCsrf.body()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-12#B1 step-up 이 필요한 ADMIN 토큰은 관리 쓰기가 403 step_up_required")
	void stepUpAdminCannotWrite() {
		User admin = fixtures.admin();
		HttpResponse<String> response = api.post("/admin/users/" + fixtures.user().getId() + "/suspend")
			.bearer(fixtures.stepUpBearer(admin))
			.send();
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("step_up_required");
	}

}
