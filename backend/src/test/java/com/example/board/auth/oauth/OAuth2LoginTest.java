package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GitHubUser;
import com.example.board.support.OAuthProviderStub.GoogleUser;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;

/**
 * TASK-4#1 oauth2Login(Google OIDC·GitHub) — 성공 시 세션 생성 후 프론트로 302, 실패 시 프론트로 {@code ?error=}.
 * TASK-4#5 제공자는 WireMock stub({@link OAuthProviderStub})이고, 흐름은 실제 HTTP + 쿠키로 끝까지 탄다.
 */
@IntegrationTest
class OAuth2LoginTest {

	static final String LOGIN_OK = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok";

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserIdentityRepository identities;

	Api api;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		flow = new OAuthFlow(api, stub);
	}

	static String sub() {
		return "g-" + UUID.randomUUID();
	}

	static long githubId() {
		return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
	}

	private Long userIdOf(AuthProvider provider, String subject) {
		return jdbc.queryForObject("select user_id from user_identities where provider = ? and subject = ?", Long.class,
				provider.name(), subject);
	}

	private HttpResponse<String> whoami(String session) {
		return api.get("/test/whoami").cookie(session).send();
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 Google 인가 시작: 302 → 제공자 인가 엔드포인트, redirect_uri 는 요청 Host 가 아니라 공개 주소 기준, state·nonce 를 익명 세션에 저장")
	void googleAuthorizationRedirect() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");

		assertThat(authorization.location().toString()).startsWith(stub.url("/google/authorize?"));
		assertThat(authorization.param("response_type")).isEqualTo("code");
		assertThat(authorization.param("client_id")).isEqualTo(OAuthProviderStub.GOOGLE_CLIENT_ID);
		assertThat(authorization.param("scope")).contains("openid").contains("email").contains("profile");
		assertThat(authorization.param("redirect_uri"))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/login/oauth2/code/google");
		assertThat(authorization.param("state")).isNotBlank();
		assertThat(authorization.param("nonce")).isNotBlank();
		assertThat(flow.session()).as("인가 요청(state)은 HttpSession 에 둔다").isNotNull();
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 GitHub 인가 시작: 302 → 제공자 인가 엔드포인트, scope read:user, 공개 주소 redirect_uri")
	void githubAuthorizationRedirect() {
		Authorization authorization = flow.authorize("/oauth2/authorization/github");

		assertThat(authorization.location().toString()).startsWith(stub.url("/github/authorize?"));
		assertThat(authorization.param("client_id")).isEqualTo(OAuthProviderStub.GITHUB_CLIENT_ID);
		assertThat(authorization.param("scope")).isEqualTo("read:user");
		assertThat(authorization.param("redirect_uri"))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/login/oauth2/code/github");
		assertThat(authorization.param("state")).isNotBlank();
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 Google 로그인 성공: 프론트로 302(?status=ok), 세션 ID 교체 + 인증된 SESSION, 로그인 전 세션은 무효")
	void googleLogin() {
		String sub = sub();
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String anonymous = flow.session();
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser(sub, "alice@gmail.test", true, "Alice Kim"));

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		assertThat(flow.session()).isNotNull().isNotEqualTo(anonymous);
		assertThat(Api.setCookie(callback, "SESSION"))
			.hasValueSatisfying(cookie -> assertThat(cookie).containsIgnoringCase("HttpOnly"));
		assertThat(Api.cookie(callback, "XSRF-TOKEN")).as("CSRF 토큰도 교체").hasValueSatisfying(t -> assertThat(t).isNotBlank());
		Long userId = userIdOf(AuthProvider.GOOGLE, sub);
		assertThat(whoami(flow.session()).body()).contains("\"id\":" + userId);
		assertThat(whoami(anonymous).statusCode()).isEqualTo(401);
		assertThat(api.get("/auth/me").cookie(flow.session()).send().body()).contains("\"providers\":[\"GOOGLE\"]");
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 GitHub 로그인 성공: subject 는 숫자 id 문자열, 공개 이메일이 없어도(null) 로그인된다")
	void githubLogin() {
		long id = githubId();

		HttpResponse<String> callback = flow.github(new GitHubUser(id, "octo-" + id, null, null));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		Long userId = userIdOf(AuthProvider.GITHUB, Long.toString(id));
		assertThat(userId).isNotNull();
		assertThat(whoami(flow.session()).body()).contains("\"id\":" + userId);
		assertThat(jdbc.queryForObject("select email from user_identities where provider = 'GITHUB' and subject = ?",
				String.class, Long.toString(id))).isNull();
	}

	@Test
	@DisplayName("TASK-4#1 제공자가 거부(error=access_denied)하면 프론트로 302 ?error=access_denied, 로그인되지 않는다")
	void providerDenied() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");

		HttpResponse<String> callback = flow.callback("google",
				"error=access_denied&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "access_denied");
		assertThat(whoami(flow.session()).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-4#1 시작하지 않은(state 가 세션에 없는) 콜백은 프론트로 302 ?error=authorization_request_not_found")
	void callbackWithoutAuthorizationRequest() {
		HttpResponse<String> callback = flow.callback("google", "code=" + OAuthFlow.newCode() + "&state=forged");

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "authorization_request_not_found");
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 id_token 서명이 제공자 JWKS 키와 맞지 않으면 실패 리다이렉트(?error=invalid_id_token), 계정을 만들지 않는다")
	void idTokenSignedByUnknownKey() {
		String sub = sub();
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser(sub, null, false, "Mallory"), OAuthProviderStub.newKey("stub-key"));

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_id_token");
		assertThat(jdbc.queryForObject("select count(*) from user_identities where subject = ?", Integer.class, sub))
			.isZero();
	}

	@Test
	@DisplayName("TASK-4#1 TASK-4#5 id_token 의 nonce 가 인가 요청과 다르면 실패 리다이렉트(?error=invalid_nonce)")
	void idTokenWithWrongNonce() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), "replayed-nonce", new GoogleUser(sub(), null, false, "Eve"));

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_nonce");
	}

	@Test
	@DisplayName("TASK-4#1 SUSPENDED 계정의 identity 로 로그인하면 프론트로 302 ?error=suspended, 세션은 인증되지 않는다")
	void suspendedUser() {
		User suspended = fixtures.suspended();
		String sub = sub();
		identities.saveAndFlush(UserIdentity.link(suspended, AuthProvider.GOOGLE, sub, null));

		HttpResponse<String> callback = flow.google(new GoogleUser(sub, null, false, "Suspended"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "suspended");
		assertThat(whoami(flow.session()).statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-4#1 설정되지 않은 제공자(client-id 없음)로 시작하면 프론트로 302 ?error=unknown_provider")
	void unconfiguredProvider() {
		HttpResponse<String> response = flow.get("/oauth2/authorization/facebook");

		assertThat(OAuthFlow.location(response)).isEqualTo(LOGIN_ERROR + "unknown_provider");
	}

	@Test
	@DisplayName("TASK-4#1 oauth2Login 을 켜도 스프링 기본 로그인 페이지는 없다: /login 은 401 JSON")
	void noDefaultLoginPage() {
		HttpResponse<String> response = api.get("/login").header("Accept", "text/html").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(response.body()).contains("\"error\":\"unauthorized\"");
	}

	@Test
	@DisplayName("TASK-4#1 미인증 API 요청은 여전히 401 JSON (OAuth 인가 시작으로 리다이렉트하지 않는다)")
	void apiStillReturns401() {
		HttpResponse<String> response = api.get("/auth/me").header("Accept", "text/html").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(response.body()).contains("\"error\":\"unauthorized\"");
	}

}
