package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/**
 * TASK-8#4 게시판 보안 매트릭스: 글·카테고리·태그 읽기는 익명도 200, 쓰기는 인증 + (세션이면) CSRF 가 필요하다.
 * 기대값은 (익명, 세션, Bearer) 순서. 대상 글은 행위자 본인의 글.
 */
@IntegrationTest
@DisplayName("TASK-8#4 게시판 보안 매트릭스")
class BoardSecurityMatrixTest {

	enum Actor {
		ANONYMOUS, SESSION, BEARER
	}

	enum Case {

		LIST("GET", "/posts", null, true, 200, 200, 200),
		DETAIL("GET", "/posts/{id}", null, true, 200, 200, 200),
		CATEGORIES("GET", "/categories", null, true, 200, 200, 200),
		TAGS("GET", "/tags", null, true, 200, 200, 200),
		CREATE("POST", "/posts", "{\"title\":\"t\",\"content\":\"c\"}", true, 401, 201, 201),
		UPDATE("PATCH", "/posts/{id}", "{\"title\":\"t2\"}", true, 401, 200, 200),
		DELETE("DELETE", "/posts/{id}", null, true, 401, 204, 204),
		/** 익명은 CSRF 검사가 인증보다 먼저라 403, 세션은 403, Bearer 는 면제. */
		CREATE_NO_CSRF("POST", "/posts", "{\"title\":\"t\",\"content\":\"c\"}", false, 403, 403, 201),
		UPDATE_NO_CSRF("PATCH", "/posts/{id}", "{\"title\":\"t2\"}", false, 403, 403, 200),
		DELETE_NO_CSRF("DELETE", "/posts/{id}", null, false, 403, 403, 204);

		final String method;

		final String path;

		final String body;

		final boolean csrf;

		final int[] expected;

		Case(String method, String path, String body, boolean csrf, int anonymous, int session, int bearer) {
			this.method = method;
			this.path = path;
			this.body = body;
			this.csrf = csrf;
			this.expected = new int[] { anonymous, session, bearer };
		}

	}

	static Stream<Arguments> matrix() {
		return Arrays.stream(Case.values())
			.flatMap(c -> Arrays.stream(Actor.values()).map(a -> Arguments.of(a, c, c.expected[a.ordinal()])));
	}

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	@ParameterizedTest(name = "TASK-8#4 {0} × {1} → {2}")
	@MethodSource("matrix")
	void matrix(Actor actor, Case scenario, int expectedStatus) {
		User user = fixtures.user();
		long postId = board.post(user.getId(), null, "매트릭스");
		Api.Request request = api.method(scenario.method, scenario.path.replace("{id}", String.valueOf(postId)));
		switch (actor) {
			case ANONYMOUS -> {
			}
			case SESSION -> request.cookie(fixtures.sessionCookie(user));
			case BEARER -> request.bearer(fixtures.bearer(user));
		}
		if (scenario.csrf && !"GET".equals(scenario.method)) {
			request.csrf(Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow());
		}
		if (scenario.body != null) {
			request.json(scenario.body);
		}

		HttpResponse<String> response = request.send();

		assertThat(response.statusCode()).as("%s × %s, body=%s", actor, scenario, response.body())
			.isEqualTo(expectedStatus);
	}

}
