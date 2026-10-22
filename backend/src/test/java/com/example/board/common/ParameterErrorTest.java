package com.example.board.common;

import static com.example.board.common.ErrorContractTest.assertContract;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/**
 * TASK-20#4 (BE-6) 쿼리·경로 파라미터 오류는 모두 400 {@code validation} + {@code fields}(파라미터 이름). enum 파라미터는 대소문자를
 * 가리지 않는다.
 */
@IntegrationTest
class ParameterErrorTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		adminBearer = fixtures.bearer(fixtures.admin());
	}

	private static void hasField(JsonNode body, String name) {
		assertThat(body.get("fields").has(name)).as(body.toString()).isTrue();
	}

	@Test
	@DisplayName("TASK-20#4 숫자 파라미터 형식 오류(?page=abc, ?size=x)는 400 validation + fields")
	void numberFormat() {
		hasField(assertContract(api.get("/posts?page=abc").send(), 400, "validation"), "page");
		hasField(assertContract(api.get("/posts/1/comments?size=x").send(), 400, "validation"), "size");
		hasField(assertContract(api.get("/admin/actions?page=1.5").bearer(adminBearer).send(), 400, "validation"),
				"page");
	}

	@Test
	@DisplayName("TASK-20#4 경로 변수 형식 오류(/posts/abc)도 400 validation + fields")
	void pathVariableFormat() {
		hasField(assertContract(api.get("/posts/abc").send(), 400, "validation"), "id");
	}

	@Test
	@DisplayName("TASK-20#4 없는 enum 값(?status=NOPE)은 400 validation + fields")
	void unknownEnum() {
		hasField(assertContract(api.get("/admin/users?status=NOPE").bearer(adminBearer).send(), 400, "validation"),
				"status");
	}

	@Test
	@DisplayName("TASK-20#4 enum 파라미터는 대소문자를 가리지 않는다(?status=suspended)")
	void enumIgnoresCase() {
		User suspended = fixtures.suspended();
		for (String value : new String[] { "suspended", "Suspended", " SUSPENDED " }) {
			HttpResponse<String> response = api.get("/admin/users?size=100&status=" + value.replace(" ", "%20"))
				.bearer(adminBearer)
				.send();
			assertThat(response.statusCode()).as(value + " " + response.body()).isEqualTo(200);
			JsonNode items = AuthClient.json(response).get("items");
			assertThat(items.valueStream().map(item -> item.get("status").asString())).isNotEmpty()
				.allMatch("SUSPENDED"::equals);
			assertThat(items.valueStream().map(item -> item.get("id").asLong())).contains(suspended.getId());
		}
	}

	@Test
	@DisplayName("TASK-20#4 지원하지 않는 ?token= 값은 400 validation + fields")
	void unsupportedTokenMode() {
		AuthClient auth = new AuthClient(api);
		HttpResponse<String> response = api.post("/auth/login?token=xyz")
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(AuthClient.uniqueEmail(), AuthClient.PASSWORD))
			.send();
		hasField(assertContract(response, 400, "validation"), "token");

		HttpResponse<String> verify = api.post("/auth/phone/verify?token=xyz")
			.csrf(auth.csrfToken())
			.json("{\"challengeId\":\"x\",\"code\":\"123456\"}")
			.send();
		hasField(assertContract(verify, 400, "validation"), "token");
	}

	@Test
	@DisplayName("TASK-20#4 범위·값 오류(page·size·limit·sort·q)도 400 validation + fields{파라미터 이름}")
	void rangeErrorsHaveFields() {
		String[][] cases = { { "/posts?page=-1", "page" }, { "/posts?size=0", "size" }, { "/posts?size=51", "size" },
				{ "/posts?sort=bogus", "sort" }, { "/posts?q=a", "q" }, { "/posts/1/comments?page=-1", "page" },
				{ "/posts/1/comments?size=101", "size" }, { "/tags?limit=0", "limit" } };
		for (String[] c : cases) {
			hasField(assertContract(api.get(c[0]).send(), 400, "validation"), c[1]);
		}
		String[][] adminCases = { { "/admin/users?page=-1", "page" }, { "/admin/users?size=0", "size" },
				{ "/admin/actions?size=1000", "size" } };
		for (String[] c : adminCases) {
			hasField(assertContract(api.get(c[0]).bearer(adminBearer).send(), 400, "validation"), c[1]);
		}
	}

}
