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
import com.example.board.support.IntegrationTest;
import com.example.board.support.ProtectedResourceTestController;
import com.example.board.user.User;

/**
 * TASK-2#5 보안 매트릭스: (익명 / 세션 / Bearer) × (읽기 / 쓰기 / 남의 리소스 / 정지 / CSRF 누락) → 기대 상태 코드.
 *
 * <p>세션은 Spring Session JDBC 에 저장한 진짜 {@code SESSION} 쿠키로, Bearer 는 운영 {@code JwtTokenService} 토큰으로 보낸다.
 * 새 보호 리소스(게시글·댓글 등)를 만들 때 이 표에 행을 더하거나 같은 형태의 매트릭스를 만든다.
 */
@IntegrationTest
@DisplayName("TASK-2#5 보안 매트릭스")
class SecurityMatrixTest {

	enum Actor {
		ANONYMOUS, SESSION, BEARER
	}

	enum Who {
		USER, ADMIN, SUSPENDED
	}

	enum Target {
		COLLECTION, OWN, OTHERS
	}

	/** 행 하나 = 시나리오. 기대값은 (익명, 세션, Bearer) 순서. 세션 쓰기는 csrf=true 면 유효한 CSRF 토큰을 붙인다. */
	enum Case {

		READ_OWN("읽기", Who.USER, "GET", Target.OWN, true, 401, 200, 200),
		READ_OTHERS("남의 리소스 읽기", Who.USER, "GET", Target.OTHERS, true, 401, 200, 200),
		CREATE("쓰기(POST)", Who.USER, "POST", Target.COLLECTION, true, 401, 201, 201),
		MODIFY_OWN("본인 수정(PATCH)", Who.USER, "PATCH", Target.OWN, true, 401, 200, 200),
		REPLACE_OWN("본인 수정(PUT)", Who.USER, "PUT", Target.OWN, true, 401, 200, 200),
		DELETE_OWN("본인 삭제", Who.USER, "DELETE", Target.OWN, true, 401, 204, 204),
		/** TASK-2#B4 */
		MODIFY_OTHERS("B4 남의 리소스 수정(USER)", Who.USER, "PATCH", Target.OTHERS, true, 401, 403, 403),
		/** TASK-2#B4 */
		DELETE_OTHERS("B4 남의 리소스 삭제(USER)", Who.USER, "DELETE", Target.OTHERS, true, 401, 403, 403),
		/** TASK-2#B4 */
		ADMIN_MODIFY_OTHERS("B4 남의 리소스 수정(ADMIN)", Who.ADMIN, "PATCH", Target.OTHERS, true, 401, 200, 200),
		/** TASK-2#B4 */
		ADMIN_DELETE_OTHERS("B4 남의 리소스 삭제(ADMIN)", Who.ADMIN, "DELETE", Target.OTHERS, true, 401, 204, 204),
		/** TASK-2#B3 */
		SUSPENDED_READ("B3 정지 계정 읽기", Who.SUSPENDED, "GET", Target.OWN, true, 401, 200, 200),
		/** TASK-2#B3 */
		SUSPENDED_CREATE("B3 정지 계정 쓰기", Who.SUSPENDED, "POST", Target.COLLECTION, true, 401, 403, 403),
		/** TASK-2#B3 */
		SUSPENDED_MODIFY_OWN("B3 정지 계정 본인 수정", Who.SUSPENDED, "PATCH", Target.OWN, true, 401, 403, 403),
		/** TASK-2#B3 */
		SUSPENDED_DELETE_OWN("B3 정지 계정 본인 삭제", Who.SUSPENDED, "DELETE", Target.OWN, true, 401, 403, 403),
		/** TASK-2#B1 TASK-2#B2: 세션은 403, Bearer 는 CSRF 면제. 익명은 CSRF 검사가 인증보다 먼저라 403. */
		CREATE_NO_CSRF("B1/B2 CSRF 누락 쓰기", Who.USER, "POST", Target.COLLECTION, false, 403, 403, 201),
		/** TASK-2#B1 TASK-2#B2 */
		MODIFY_OWN_NO_CSRF("B1/B2 CSRF 누락 본인 수정", Who.USER, "PATCH", Target.OWN, false, 403, 403, 200),
		/** TASK-2#B1 TASK-2#B2 */
		DELETE_OWN_NO_CSRF("B1/B2 CSRF 누락 본인 삭제", Who.USER, "DELETE", Target.OWN, false, 403, 403, 204);

		final String label;

		final Who who;

		final String method;

		final Target target;

		final boolean csrf;

		final int[] expected;

		Case(String label, Who who, String method, Target target, boolean csrf, int anonymous, int session, int bearer) {
			this.label = label;
			this.who = who;
			this.method = method;
			this.target = target;
			this.csrf = csrf;
			this.expected = new int[] { anonymous, session, bearer };
		}

		@Override
		public String toString() {
			return label;
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
	ProtectedResourceTestController resources;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	@ParameterizedTest(name = "TASK-2#5 {0} × {1} → {2}")
	@MethodSource("matrix")
	void matrix(Actor actor, Case scenario, int expectedStatus) {
		User actingUser = switch (scenario.who) {
			case USER -> fixtures.user();
			case ADMIN -> fixtures.admin();
			case SUSPENDED -> fixtures.suspended();
		};
		String path = switch (scenario.target) {
			case COLLECTION -> "/test/resources";
			case OWN -> "/test/resources/" + resources.createOwnedBy(actingUser.getId());
			case OTHERS -> "/test/resources/" + resources.createOwnedBy(fixtures.user().getId());
		};

		Api.Request request = api.method(scenario.method, path);
		switch (actor) {
			case ANONYMOUS -> {
			}
			case SESSION -> request.cookie(fixtures.sessionCookie(actingUser));
			case BEARER -> request.bearer(fixtures.bearer(actingUser));
		}
		if (scenario.csrf && !"GET".equals(scenario.method)) {
			request.csrf(Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow());
		}

		HttpResponse<String> response = request.send();

		assertThat(response.statusCode()).as("%s × %s, body=%s", actor, scenario, response.body())
			.isEqualTo(expectedStatus);
	}

}
