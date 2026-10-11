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
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/**
 * TASK-9#1·#2 댓글·좋아요 보안 매트릭스: 댓글 목록은 익명도 200, 쓰기(댓글 작성·삭제, 좋아요·취소)는 인증 + (세션이면) CSRF.
 * 정지 계정(SUSPENDED)과 제한(step-up) 토큰은 쓰기가 403. 기대값은 (익명, 세션, Bearer, 정지 Bearer, step-up Bearer) 순서.
 * 대상 글·댓글은 행위자 본인의 것.
 */
@IntegrationTest
@DisplayName("TASK-9#1 TASK-9#2 댓글·좋아요 보안 매트릭스")
class InteractionSecurityMatrixTest {

	enum Actor {
		ANONYMOUS, SESSION, BEARER, SUSPENDED, STEP_UP
	}

	enum Case {

		COMMENTS("GET", "/posts/{post}/comments", null, true, 200, 200, 200, 200, 200),
		COMMENT_CREATE("POST", "/posts/{post}/comments", "{\"content\":\"c\"}", true, 401, 201, 201, 403, 403),
		COMMENT_DELETE("DELETE", "/comments/{comment}", null, true, 401, 204, 204, 403, 403),
		LIKE("POST", "/posts/{post}/like", null, true, 401, 200, 200, 403, 403),
		UNLIKE("DELETE", "/posts/{post}/like", null, true, 401, 200, 200, 403, 403),
		/** 익명·세션은 CSRF 가 없으면 403, Bearer 는 면제. */
		COMMENT_CREATE_NO_CSRF("POST", "/posts/{post}/comments", "{\"content\":\"c\"}", false, 403, 403, 201, 403, 403),
		LIKE_NO_CSRF("POST", "/posts/{post}/like", null, false, 403, 403, 200, 403, 403);

		final String method;

		final String path;

		final String body;

		final boolean csrf;

		final int[] expected;

		Case(String method, String path, String body, boolean csrf, int... expected) {
			this.method = method;
			this.path = path;
			this.body = body;
			this.csrf = csrf;
			this.expected = expected;
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

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	@ParameterizedTest(name = "TASK-9#1 TASK-9#2 {0} × {1} → {2}")
	@MethodSource("matrix")
	void matrix(Actor actor, Case scenario, int expectedStatus) {
		User user = actor == Actor.SUSPENDED ? fixtures.suspended() : fixtures.user();
		long postId = board.post(user.getId(), null, "매트릭스");
		Long commentId = jdbc.queryForObject(
				"insert into comments (post_id, author_id, content) values (?, ?, 'c') returning id", Long.class, postId,
				user.getId());
		String path = scenario.path.replace("{post}", String.valueOf(postId))
			.replace("{comment}", String.valueOf(commentId));
		Api.Request request = api.method(scenario.method, path);
		switch (actor) {
			case ANONYMOUS -> {
			}
			case SESSION -> request.cookie(fixtures.sessionCookie(user));
			case BEARER, SUSPENDED -> request.bearer(fixtures.bearer(user));
			case STEP_UP -> request.bearer(fixtures.stepUpBearer(user));
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
