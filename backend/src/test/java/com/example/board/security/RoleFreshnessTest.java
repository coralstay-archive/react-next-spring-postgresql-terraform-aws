package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.ProtectedResourceTestController;
import com.example.board.user.User;

/**
 * TASK-24#2 (BE-16) 역할은 세션·토큰에 실린 발급 시점 값이 아니라 요청마다 DB 의 최신 값으로 판정한다. 역할 변경 API 가 없어(DB 작업)
 * "변경 시 세션 삭제"를 걸 곳이 없고 access JWT 는 지울 수 없으므로, 정지(SuspendedUserWriteFilter)처럼 요청마다 대조한다.
 */
@IntegrationTest
class RoleFreshnessTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ProtectedResourceTestController resources;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private void setRole(User user, String role) {
		jdbc.update("update users set role = ? where id = ?", role, user.getId());
	}

	@Test
	@DisplayName("TASK-24#2 TASK-24#B2 ADMIN 에서 강등되면 같은 세션·JWT 의 다음 관리자 API 요청이 403 forbidden")
	void demotedAdminIsForbiddenNextRequest() {
		User admin = fixtures.admin();
		String session = fixtures.sessionCookie(admin);
		String bearer = fixtures.bearer(admin);
		assertThat(api.get("/admin/users").cookie(session).send().statusCode()).isEqualTo(200);
		assertThat(api.get("/admin/users").bearer(bearer).send().statusCode()).isEqualTo(200);

		setRole(admin, "USER");

		for (HttpResponse<String> response : new HttpResponse[] { api.get("/admin/users").cookie(session).send(),
				api.get("/admin/users").bearer(bearer).send() }) {
			assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
			assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("forbidden");
		}
	}

	@Test
	@DisplayName("TASK-24#2 TASK-24#B2 강등된 ADMIN 은 @PreAuthorize·소유권(ADMIN 이면 남의 리소스 수정)·principal 에서도 USER 다")
	void demotedAdminLosesAdminEverywhere() {
		User admin = fixtures.admin();
		String bearer = fixtures.bearer(admin);
		long othersResource = resources.createOwnedBy(fixtures.user().getId());
		assertThat(api.get("/test/admin-only").bearer(bearer).send().statusCode()).isEqualTo(200);
		setRole(admin, "USER");

		assertThat(api.get("/test/admin-only").bearer(bearer).send().statusCode()).isEqualTo(403);
		assertThat(api.patch("/test/resources/" + othersResource).bearer(bearer).send().statusCode()).isEqualTo(403);
		HttpResponse<String> whoami = api.get("/test/whoami").bearer(bearer).send();
		assertThat(AuthClient.json(whoami).get("role").asString()).isEqualTo("USER");
	}

	@Test
	@DisplayName("TASK-24#2 승격도 다음 요청부터 반영된다(USER 세션·JWT 로 관리자 API 200)")
	void promotedUserGetsAdminNextRequest() {
		User user = fixtures.user();
		String session = fixtures.sessionCookie(user);
		String bearer = fixtures.bearer(user);
		assertThat(api.get("/admin/users").bearer(bearer).send().statusCode()).isEqualTo(403);

		setRole(user, "ADMIN");

		assertThat(api.get("/admin/users").cookie(session).send().statusCode()).isEqualTo(200);
		assertThat(api.get("/admin/users").bearer(bearer).send().statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-24#2 계정이 지워졌으면 세션·JWT 가 남아 있어도 보호된 API 는 401")
	void deletedUserIsUnauthenticated() {
		User user = fixtures.user();
		String session = fixtures.sessionCookie(user);
		String bearer = fixtures.bearer(user);
		jdbc.update("delete from users where id = ?", user.getId());

		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(401);
		assertThat(api.get("/test/whoami").bearer(bearer).send().statusCode()).isEqualTo(401);
	}

}
