package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

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

/** TASK-3#2 POST /api/auth/login: 기본은 세션 생성, {@code ?token=jwt} 면 access+refresh 발급. */
@IntegrationTest
class LoginTest {

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

	private long userId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	private static String sha256Hex(String value) throws Exception {
		return HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("TASK-3#2 세션 로그인: 200 + 사용자 요약 + HttpOnly SESSION 쿠키, 그 쿠키로 인증된다")
	void sessionLogin() {
		String email = auth.signedUp();

		HttpResponse<String> response = auth.sessionLogin(email.toUpperCase(), AuthClient.PASSWORD);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("id").asLong()).isEqualTo(userId(email));
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.has("accessToken")).isFalse();
		assertThat(Api.setCookie(response, "SESSION"))
			.hasValueSatisfying(cookie -> assertThat(cookie).containsIgnoringCase("HttpOnly"));

		String session = "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();
		HttpResponse<String> whoami = api.get("/test/whoami").cookie(session).send();
		assertThat(whoami.statusCode()).isEqualTo(200);
		assertThat(whoami.body()).contains("\"id\":" + userId(email));
	}

	@Test
	@DisplayName("TASK-3#2 세션 로그인은 CSRF 토큰이 없으면 403 (로그인 CSRF 방어)")
	void sessionLoginRequiresCsrf() {
		String email = auth.signedUp();

		HttpResponse<String> response = api.post("/auth/login")
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
	}

	@Test
	@DisplayName("TASK-3#2 세션 로그인은 세션 ID 를 바꾼다(세션 고정 방지): 로그인 전 세션 쿠키는 더 이상 쓸 수 없다")
	void sessionFixationProtection() {
		String before = fixtures.sessionCookie(fixtures.user());
		String email = auth.signedUp();

		HttpResponse<String> response = api.post("/auth/login")
			.cookie(before)
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		String after = "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();
		assertThat(after).isNotEqualTo(before);
		assertThat(api.get("/test/whoami").cookie(before).send().statusCode()).isEqualTo(401);
		assertThat(api.get("/test/whoami").cookie(after).send().body()).contains("\"id\":" + userId(email));
	}

	@Test
	@DisplayName("TASK-3#2 세션 로그인은 CSRF 토큰도 새로 발급한다(XSRF-TOKEN 쿠키 교체)")
	void sessionLoginRotatesCsrfToken() {
		String email = auth.signedUp();
		String csrf = auth.csrfToken();

		HttpResponse<String> response = api.post("/auth/login")
			.csrf(csrf)
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(Api.cookie(response, "XSRF-TOKEN")).hasValueSatisfying(token -> assertThat(token).isNotBlank()
			.isNotEqualTo(csrf));
	}

	@Test
	@DisplayName("TASK-3#2 ?token=jwt: 세션 없이 accessToken·refreshToken·tokenType·expiresIn 을 돌려준다")
	void jwtLogin() throws Exception {
		String email = auth.signedUp();

		HttpResponse<String> response = auth.jwtLogin(email, AuthClient.PASSWORD);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
		assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
		String access = body.get("accessToken").asString();
		String refresh = body.get("refreshToken").asString();
		assertThat(refresh).hasSizeGreaterThanOrEqualTo(43).doesNotContain(".");

		HttpResponse<String> whoami = api.get("/test/whoami").bearer(access).send();
		assertThat(whoami.statusCode()).isEqualTo(200);
		assertThat(whoami.body()).contains("\"id\":" + userId(email));

		// refresh 토큰은 원문이 아니라 SHA-256 해시만 저장한다
		Map<String, Object> row = jdbc.queryForMap(
				"select user_id, family_id, expires_at, revoked_at, replaced_by from refresh_tokens where token_hash = ?",
				sha256Hex(refresh));
		assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId(email));
		assertThat(row.get("family_id")).isNotNull();
		assertThat(row.get("revoked_at")).isNull();
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
				refresh))
			.isZero();
	}

	@Test
	@DisplayName("TASK-3#2 ?token=jwt 로그인마다 새 refresh family 를 만든다, 비브라우저 흐름이라 CSRF 를 요구하지 않는다")
	void jwtLoginNewFamilyEachTime() throws Exception {
		String email = auth.signedUp();

		String first = auth.tokens(email).get("refreshToken").asString();
		String second = auth.tokens(email).get("refreshToken").asString();

		List<Object> families = jdbc.queryForList(
				"select family_id from refresh_tokens where token_hash in (?, ?)", Object.class, sha256Hex(first),
				sha256Hex(second));
		assertThat(families).hasSize(2).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("TASK-3#2 token 파라미터가 jwt 가 아니면 400")
	void unknownTokenMode() {
		String email = auth.signedUp();

		HttpResponse<String> response = api.post("/auth/login?token=opaque")
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-3#2 정지(SUSPENDED) 계정은 비밀번호가 맞아도 로그인할 수 없다(세션·JWT 모두 403)")
	void suspendedCannotLogIn() {
		String email = auth.signedUp();
		jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId(email));

		HttpResponse<String> session = auth.sessionLogin(email, AuthClient.PASSWORD);
		HttpResponse<String> jwt = auth.jwtLogin(email, AuthClient.PASSWORD);

		assertThat(session.statusCode()).isEqualTo(403);
		assertThat(session.body()).contains("\"error\":\"suspended\"");
		assertThat(Api.setCookie(session, "SESSION")).isEmpty();
		assertThat(jwt.statusCode()).isEqualTo(403);
		assertThat(jwt.body()).doesNotContain("accessToken");
	}

	@Test
	@DisplayName("TASK-3#2 TASK-3#B2 실패 응답은 계정 존재 여부를 드러내지 않는다: 없는 이메일·틀린 비밀번호·LOCAL 없는 계정 모두 같은 401")
	void failureDoesNotRevealAccount() {
		String email = auth.signedUp();
		// 이메일은 같지만 LOCAL identity 가 없는(소셜 전용) 계정
		User social = fixtures.user();
		String socialEmail = AuthClient.uniqueEmail();
		identities.saveAndFlush(UserIdentity.link(social, AuthProvider.GOOGLE, "google-" + social.getId(), socialEmail));
		// 정지 계정이라도 비밀번호가 틀리면 401 (정지 여부도 드러내지 않는다)
		String suspended = auth.signedUp();
		jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId(suspended));

		List<HttpResponse<String>> responses = List.of(auth.sessionLogin(AuthClient.uniqueEmail(), AuthClient.PASSWORD),
				auth.sessionLogin(email, "wr0ng-password"), auth.sessionLogin(socialEmail, AuthClient.PASSWORD),
				auth.sessionLogin(suspended, "wr0ng-password"), auth.jwtLogin(AuthClient.uniqueEmail(), AuthClient.PASSWORD),
				auth.jwtLogin(email, "wr0ng-password"), auth.jwtLogin(socialEmail, AuthClient.PASSWORD));

		String expectedBody = responses.get(0).body();
		assertThat(expectedBody).contains("\"error\":\"invalid_credentials\"");
		for (HttpResponse<String> response : responses) {
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(response.body()).isEqualTo(expectedBody);
			assertThat(Api.setCookie(response, "SESSION")).isEmpty();
		}
	}

	@Test
	@DisplayName("TASK-3#2 TASK-3#B2 빈 값·형식 오류 입력도 같은 401 이 아니라 400 검증 오류다(계정 조회 전 단계)")
	void blankInputIs400() {
		HttpResponse<String> response = api.post("/auth/login?token=jwt").json("{\"email\":\"\",\"password\":\"\"}").send();

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-3#2 TASK-3#B4 비밀번호는 DelegatingPasswordEncoder 의 {bcrypt} 해시로만 저장된다")
	void passwordStoredAsDelegatingBcrypt() {
		String email = auth.signedUp();

		String hash = jdbc.queryForObject("""
				select c.password_hash from local_credentials c join user_identities i on i.id = c.identity_id
				where i.provider = 'LOCAL' and i.subject = ?""", String.class, email);

		assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(AuthClient.PASSWORD);
		assertThat(auth.jwtLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
	}

}
