package com.example.board.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

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
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;

import tools.jackson.databind.JsonNode;

/** TASK-4#4 {@code GET /api/me/identities}, {@code DELETE /api/me/identities/{id}}. */
@IntegrationTest
class IdentitiesApiTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserIdentityRepository identities;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private UserIdentity identity(User user, AuthProvider provider, String email) {
		return identities.saveAndFlush(UserIdentity.link(user, provider, "s-" + UUID.randomUUID(), email));
	}

	private HttpResponse<String> delete(String session, Long id) {
		return api.delete("/me/identities/" + id).cookie(session).csrf(auth.csrfToken()).send();
	}

	private long localUserId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	@Test
	@DisplayName("TASK-4#4 GET /api/me/identities: 내 identity 만 연결 순서대로 {id, provider, email, linkedAt}, subject 는 내보내지 않는다")
	void list() {
		User me = fixtures.user();
		UserIdentity google = identity(me, AuthProvider.GOOGLE, "me@gmail.test");
		UserIdentity github = identity(me, AuthProvider.GITHUB, null);
		identity(fixtures.user(), AuthProvider.GOOGLE, "other@gmail.test");

		HttpResponse<String> response = api.get("/me/identities").cookie(fixtures.sessionCookie(me)).send();

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.size()).isEqualTo(2);
		JsonNode first = body.get(0);
		assertThat(first.get("id").asLong()).isEqualTo(google.getId());
		assertThat(first.get("provider").asString()).isEqualTo("GOOGLE");
		assertThat(first.get("email").asString()).isEqualTo("me@gmail.test");
		assertThat(first.get("linkedAt").asString()).isNotBlank();
		assertThat(first.has("subject")).isFalse();
		assertThat(body.get(1).get("id").asLong()).isEqualTo(github.getId());
		assertThat(body.get(1).get("email").isNull()).isTrue();
	}

	@Test
	@DisplayName("TASK-4#4 목록은 Bearer 로도 된다, 익명은 401")
	void listAuth() {
		User me = fixtures.user();
		identity(me, AuthProvider.GITHUB, null);

		assertThat(api.get("/me/identities").bearer(fixtures.bearer(me)).send().statusCode()).isEqualTo(200);
		assertThat(api.get("/me/identities").send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-4#4 DELETE: 내 identity(마지막이 아님)는 204 로 해제되고 그 수단으로는 더 이상 이 계정을 찾지 않는다")
	void unlink() {
		User me = fixtures.user();
		UserIdentity google = identity(me, AuthProvider.GOOGLE, null);
		identity(me, AuthProvider.GITHUB, null);
		String session = fixtures.sessionCookie(me);

		HttpResponse<String> response = delete(session, google.getId());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(identities.findById(google.getId())).isEmpty();
		assertThat(api.get("/auth/me").cookie(session).send().body()).contains("\"providers\":[\"GITHUB\"]");
	}

	@Test
	@DisplayName("TASK-4#4 DELETE: 남의 identity 와 없는 id 는 404 (존재 여부를 드러내지 않는다), 남의 것은 그대로")
	void othersIdentityNotFound() {
		User other = fixtures.user();
		UserIdentity theirs = identity(other, AuthProvider.GOOGLE, null);
		identity(other, AuthProvider.GITHUB, null);
		User me = fixtures.user();
		identity(me, AuthProvider.GOOGLE, null);
		String session = fixtures.sessionCookie(me);

		HttpResponse<String> response = delete(session, theirs.getId());

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("\"error\":\"identity_not_found\"");
		assertThat(identities.findById(theirs.getId())).isPresent();
		assertThat(delete(session, Long.MAX_VALUE).statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("TASK-4#4 TASK-4#B3 마지막 남은 identity 는 해제할 수 없다: 409 last_identity")
	void lastIdentity() {
		User me = fixtures.user();
		UserIdentity only = identity(me, AuthProvider.GOOGLE, null);

		HttpResponse<String> response = delete(fixtures.sessionCookie(me), only.getId());

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.body()).contains("\"error\":\"last_identity\"");
		assertThat(identities.findById(only.getId())).isPresent();
	}

	@Test
	@DisplayName("TASK-4#4 TASK-4#B3 LOCAL identity 를 해제하면 local_credentials 도 지워지고 비밀번호 로그인은 401")
	void unlinkLocalRemovesCredentials() {
		String email = auth.signedUp();
		long userId = localUserId(email);
		Long localId = jdbc.queryForObject("select id from user_identities where user_id = ? and provider = 'LOCAL'",
				Long.class, userId);
		identities.saveAndFlush(UserIdentity.link(identities.findById(localId).orElseThrow().getUser(),
				AuthProvider.GOOGLE, "s-" + UUID.randomUUID(), null));
		String session = auth.sessionCookie(email);

		HttpResponse<String> response = delete(session, localId);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from local_credentials where identity_id = ?", Integer.class,
				localId)).isZero();
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(401);
		List<String> providers = jdbc.queryForList("select provider from user_identities where user_id = ?",
				String.class, userId);
		assertThat(providers).containsExactly("GOOGLE");
	}

	@Test
	@DisplayName("TASK-4#4 세션 DELETE 는 CSRF 토큰이 필요하다(403), Bearer 는 면제")
	void csrf() {
		User me = fixtures.user();
		UserIdentity google = identity(me, AuthProvider.GOOGLE, null);
		UserIdentity github = identity(me, AuthProvider.GITHUB, null);
		identity(me, AuthProvider.KAKAO, null);

		assertThat(api.delete("/me/identities/" + google.getId()).cookie(fixtures.sessionCookie(me)).send().statusCode())
			.isEqualTo(403);
		assertThat(api.delete("/me/identities/" + github.getId()).bearer(fixtures.bearer(me)).send().statusCode())
			.isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-4#4 SUSPENDED 계정은 해제(쓰기)할 수 없다: 403")
	void suspended() {
		User me = fixtures.suspended();
		UserIdentity google = identity(me, AuthProvider.GOOGLE, null);
		identity(me, AuthProvider.GITHUB, null);

		assertThat(delete(fixtures.sessionCookie(me), google.getId()).statusCode()).isEqualTo(403);
		assertThat(identities.findById(google.getId())).isPresent();
	}

}
