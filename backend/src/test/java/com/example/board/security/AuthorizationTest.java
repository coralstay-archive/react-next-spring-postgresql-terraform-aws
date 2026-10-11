package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import com.example.board.auth.AuthUser;
import com.example.board.auth.AuthUserAuthentication;
import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.ProtectedResourceTestController;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/** TASK-2#4 역할(USER/ADMIN)·상태(ACTIVE/SUSPENDED)·OwnershipChecker 기반 공통 인가. */
@IntegrationTest
class AuthorizationTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	ProtectedResourceTestController resources;

	@Autowired
	OwnershipChecker ownership;

	@Autowired
	UserRepository users;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private String csrfToken() {
		return Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B4 본인 리소스는 수정·삭제할 수 있다")
	void ownerCanModify() {
		User owner = fixtures.user();
		String bearer = fixtures.bearer(owner);
		long id = resources.createOwnedBy(owner.getId());

		assertThat(api.patch("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(200);
		assertThat(api.put("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(200);
		assertThat(api.delete("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B4 USER 는 남의 리소스를 수정·삭제할 수 없다(403), 읽기는 된다")
	void userCannotModifyOthers() {
		long id = resources.createOwnedBy(fixtures.user().getId());
		String bearer = fixtures.bearer(fixtures.user());

		assertThat(api.get("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(200);
		assertThat(api.patch("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(403);
		assertThat(api.put("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(403);
		assertThat(api.delete("/test/resources/" + id).bearer(bearer).send().statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B4 ADMIN 은 남의 리소스를 수정·삭제할 수 있다 (세션 경로)")
	void adminCanModifyOthers() {
		long id = resources.createOwnedBy(fixtures.user().getId());
		String session = fixtures.sessionCookie(fixtures.admin());
		String csrf = csrfToken();

		assertThat(api.patch("/test/resources/" + id).cookie(session).csrf(csrf).send().statusCode()).isEqualTo(200);
		assertThat(api.delete("/test/resources/" + id).cookie(session).csrf(csrf).send().statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B3 SUSPENDED 사용자는 자기 리소스라도 모든 쓰기가 403 이다 (Bearer)")
	void suspendedCannotWriteBearer() {
		User suspended = fixtures.suspended();
		String bearer = fixtures.bearer(suspended);
		long own = resources.createOwnedBy(suspended.getId());

		assertThat(api.post("/test/resources").bearer(bearer).send().statusCode()).isEqualTo(403);
		for (String method : new String[] { "PUT", "PATCH", "DELETE" }) {
			assertThat(api.method(method, "/test/resources/" + own).bearer(bearer).send().statusCode()).as(method)
				.isEqualTo(403);
			assertThat(api.method(method, "/test/echo").bearer(bearer).send().statusCode()).as(method).isEqualTo(403);
		}
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B3 SUSPENDED 사용자는 유효한 CSRF 가 있어도 세션 쓰기가 403 이다")
	void suspendedCannotWriteSession() {
		User suspended = fixtures.suspended();
		String session = fixtures.sessionCookie(suspended);
		String csrf = csrfToken();
		long own = resources.createOwnedBy(suspended.getId());

		HttpResponse<String> response = api.post("/test/resources").cookie(session).csrf(csrf).send();
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"suspended\"");
		assertThat(api.patch("/test/resources/" + own).cookie(session).csrf(csrf).send().statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B3 SUSPENDED 사용자도 읽기는 된다")
	void suspendedCanRead() {
		User suspended = fixtures.suspended();
		long own = resources.createOwnedBy(suspended.getId());

		assertThat(api.get("/test/whoami").bearer(fixtures.bearer(suspended)).send().statusCode()).isEqualTo(200);
		assertThat(api.get("/test/resources/" + own).cookie(fixtures.sessionCookie(suspended)).send().statusCode())
			.isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B3 토큰·세션 발급 후 정지된 사용자도 DB 상태 기준으로 쓰기가 403 이다 (ADMIN 포함)")
	void suspensionAfterLoginIsEnforced() {
		User admin = fixtures.admin();
		String bearer = fixtures.bearer(admin);
		String session = fixtures.sessionCookie(admin);
		long others = resources.createOwnedBy(fixtures.user().getId());
		assertThat(api.post("/test/echo").bearer(bearer).send().statusCode()).isEqualTo(200);

		admin.suspend();
		users.saveAndFlush(admin);

		assertThat(api.post("/test/echo").bearer(bearer).send().statusCode()).isEqualTo(403);
		assertThat(api.delete("/test/resources/" + others).bearer(bearer).send().statusCode()).isEqualTo(403);
		assertThat(api.post("/test/echo").cookie(session).csrf(csrfToken()).send().statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-2#4 @PreAuthorize 역할 검사: ADMIN 전용은 USER 403, ADMIN 200")
	void roleBasedMethodSecurity() {
		assertThat(api.get("/test/admin-only").bearer(fixtures.bearer(fixtures.user())).send().statusCode())
			.isEqualTo(403);
		assertThat(api.get("/test/admin-only").cookie(fixtures.sessionCookie(fixtures.admin())).send().statusCode())
			.isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-2#4 TASK-2#B4 OwnershipChecker: 본인 또는 ADMIN 만 true, 익명·소유자 없음은 false")
	void ownershipCheckerRules() {
		var user = AuthUserAuthentication.of(new AuthUser(1L, Role.USER, UserStatus.ACTIVE));
		var admin = AuthUserAuthentication.of(new AuthUser(2L, Role.ADMIN, UserStatus.ACTIVE));
		var anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
		OwnedResource ownedBy1 = () -> 1L;

		assertThat(ownership.canModify(user, 1L)).isTrue();
		assertThat(ownership.canModify(user, 3L)).isFalse();
		assertThat(ownership.canModify(admin, 3L)).isTrue();
		assertThat(ownership.canModify(user, ownedBy1)).isTrue();
		assertThat(ownership.canModify(anonymous, 1L)).isFalse();
		assertThat(ownership.canModify(null, 1L)).isFalse();
		assertThat(ownership.canModify(user, (Long) null)).isFalse();
		assertThat(ownership.isOwner(admin, 3L)).isFalse();
	}

}
