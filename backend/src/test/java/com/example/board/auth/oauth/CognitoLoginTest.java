package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

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
import com.example.board.support.OAuthProviderStub.CognitoUser;
import com.example.board.user.User;

/**
 * TASK-6#3 Cognito 로그인 전체 흐름을 WireMock 으로 HTTP 로 탄다: 인가 시작 → Hosted UI({@code /oauth2/authorize}) 302 → 콜백 →
 * 토큰(client_secret_basic, RS256 id_token, iss = 설정한 issuer) → JWKS 검증 → userinfo → 세션. 앱에는 운영과 같은 설정
 * 4개(client-id·secret·issuer-uri·domain)만 주고 엔드포인트는 앱이 만든다.
 */
@IntegrationTest
class CognitoLoginTest {

	static final String LOGIN_OK = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok";

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	static final String ACCOUNT = OAuthFlow.PUBLIC_BASE_URL + "/account/";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		flow = new OAuthFlow(api, stub);
	}

	static String sub() {
		return UUID.randomUUID().toString();
	}

	private Long userIdOf(String subject) {
		return jdbc.query("select user_id from user_identities where provider = 'COGNITO' and subject = ?",
				rs -> rs.next() ? rs.getLong(1) : null, subject);
	}

	private Map<String, Object> user(Long id) {
		return jdbc.queryForMap("select nickname, email, role, status from users where id = ?", id);
	}

	private String identityEmail(String subject) {
		return jdbc.queryForObject("select email from user_identities where provider = 'COGNITO' and subject = ?",
				String.class, subject);
	}

	private String whoami(String session) {
		return api.get("/test/whoami").cookie(session).send().body();
	}

	private int users() {
		return jdbc.queryForObject("select count(*) from users", Integer.class);
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 Cognito 인가 시작: 302 → {COGNITO_DOMAIN}/oauth2/authorize, client_id·scope openid email profile·공개 주소 redirect_uri·state·nonce")
	void authorizationRedirect() {
		Authorization authorization = flow.authorize("/oauth2/authorization/cognito");

		assertThat(authorization.location().toString()).startsWith(stub.cognitoDomain() + "/oauth2/authorize?");
		assertThat(authorization.param("response_type")).isEqualTo("code");
		assertThat(authorization.param("client_id")).isEqualTo(OAuthProviderStub.COGNITO_CLIENT_ID);
		assertThat(authorization.param("scope").split(" ")).containsExactlyInAnyOrder("openid", "email", "profile");
		assertThat(authorization.param("redirect_uri"))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/login/oauth2/code/cognito");
		assertThat(authorization.param("state")).isNotBlank();
		assertThat(authorization.param("nonce")).isNotBlank();
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 Cognito 로그인: Hosted UI → 콜백 → client_secret_basic 토큰 교환 → id_token(JWKS·iss·aud·nonce) → COGNITO identity 로 새 계정 + 세션")
	void login() {
		String sub = sub();

		HttpResponse<String> callback = flow.cognito(new CognitoUser(sub, "Alice@Example.COM", true, "앨리스"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		Long userId = userIdOf(sub);
		assertThat(userId).isNotNull();
		assertThat(user(userId)).containsEntry("nickname", "앨리스")
			.containsEntry("email", "alice@example.com")
			.containsEntry("role", "USER")
			.containsEntry("status", "ACTIVE");
		assertThat(identityEmail(sub)).isEqualTo("alice@example.com");
		assertThat(whoami(flow.session())).contains("\"id\":" + userId);
		assertThat(api.get("/auth/me").cookie(flow.session()).send().body()).contains("\"providers\":[\"COGNITO\"]");
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 email_verified 가 userinfo 의 문자열 \"true\" 뿐이어도 검증된 이메일로 users.email 에 기록")
	void stringEmailVerified() {
		String sub = sub();

		HttpResponse<String> callback = flow.cognito(new CognitoUser(sub, "str@example.com", true, null, true));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		assertThat(user(userIdOf(sub))).containsEntry("email", "str@example.com").containsEntry("nickname", "str");
	}

	@Test
	@DisplayName("TASK-6#3 email_verified \"false\" 면 users.email 은 비우고 identity 에만 이메일을 기록한다")
	void unverifiedEmail() {
		String sub = sub();

		assertThat(OAuthFlow.location(flow.cognito(new CognitoUser(sub, "unverified@example.com", false, "미인증", true))))
			.isEqualTo(LOGIN_OK);

		assertThat(user(userIdOf(sub))).containsEntry("email", null);
		assertThat(identityEmail(sub)).isEqualTo("unverified@example.com");
	}

	@Test
	@DisplayName("TASK-6#3 Cognito 재로그인은 같은 계정(sub 기준), 계정이 늘지 않는다")
	void relogin() {
		String sub = sub();
		assertThat(OAuthFlow.location(flow.cognito(new CognitoUser(sub, "re@example.com", true, "재로그인"))))
			.isEqualTo(LOGIN_OK);
		Long userId = userIdOf(sub);
		int before = users();

		OAuthFlow again = new OAuthFlow(api, stub);
		assertThat(OAuthFlow.location(again.cognito(new CognitoUser(sub, "re@example.com", true, "새이름"))))
			.isEqualTo(LOGIN_OK);

		assertThat(users()).isEqualTo(before);
		assertThat(whoami(again.session())).contains("\"id\":" + userId);
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 LOCAL 계정에 Cognito 연결(?linked=cognito), 이후 Cognito 로그인은 그 계정. 이메일이 같아도 자동 병합하지 않는다")
	void linkToLocalAccount() {
		User me = fixtures.user();
		String sub = sub();
		OAuthFlow linking = new OAuthFlow(api, stub).session(fixtures.sessionCookie(me));

		HttpResponse<String> callback = linking.cognito("/me/identities/link/cognito",
				new CognitoUser(sub, "linked@example.com", true, "연결"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?linked=cognito");
		assertThat(userIdOf(sub)).isEqualTo(me.getId());
		assertThat(api.get("/me/identities").cookie(linking.session()).send().body()).contains("\"provider\":\"COGNITO\"");
		OAuthFlow login = new OAuthFlow(api, stub);
		assertThat(OAuthFlow.location(login.cognito(new CognitoUser(sub, "linked@example.com", true, "연결"))))
			.isEqualTo(LOGIN_OK);
		assertThat(whoami(login.session())).contains("\"id\":" + me.getId());
	}

	@Test
	@DisplayName("TASK-6#3 다른 계정에 붙은 Cognito identity 는 연결할 수 없다(?error=identity_in_use)")
	void identityInUse() {
		String sub = sub();
		flow.cognito(new CognitoUser(sub, null, false, "owner"));
		Long owner = userIdOf(sub);

		HttpResponse<String> callback = new OAuthFlow(api, stub).session(fixtures.sessionCookie(fixtures.user()))
			.cognito("/me/identities/link/cognito", new CognitoUser(sub, null, false, "owner"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=identity_in_use");
		assertThat(userIdOf(sub)).isEqualTo(owner);
	}

	@Test
	@DisplayName("TASK-6#3 id_token 의 iss 가 설정한 issuer 와 다르면(다른 User Pool) ?error=invalid_id_token, 계정을 만들지 않는다")
	void invalidIssuer() {
		String sub = sub();
		Authorization authorization = flow.authorize("/oauth2/authorization/cognito");
		String code = OAuthFlow.newCode();
		stub.stubCognito(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new CognitoUser(sub, "evil@example.com", true, "evil"), stub.url("/cognito-idp/us-east-1_OtherPool"));
		int before = users();

		HttpResponse<String> callback = flow.followHostedUi(authorization, code);

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_id_token");
		assertThat(stub.cognitoTokenRequests(code)).isEqualTo(1);
		assertThat(userIdOf(sub)).isNull();
		assertThat(users()).isEqualTo(before);
		assertThat(api.get("/test/whoami").cookie(flow.session()).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-6#3 id_token 의 nonce 가 인가 요청과 다르면 ?error=invalid_nonce")
	void invalidNonce() {
		String sub = sub();
		Authorization authorization = flow.authorize("/oauth2/authorization/cognito");
		String code = OAuthFlow.newCode();
		stub.stubCognito(code, authorization.param("redirect_uri"), "replayed-nonce",
				new CognitoUser(sub, null, false, "replay"));

		HttpResponse<String> callback = flow.followHostedUi(authorization, code);

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_nonce");
		assertThat(userIdOf(sub)).isNull();
	}

	@Test
	@DisplayName("TASK-6#3 id_token 서명이 issuer JWKS 키와 맞지 않으면 ?error=invalid_id_token")
	void untrustedSigningKey() {
		String sub = sub();
		Authorization authorization = flow.authorize("/oauth2/authorization/cognito");
		String code = OAuthFlow.newCode();
		stub.stubCognito(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new CognitoUser(sub, null, false, "forged"), OAuthProviderStub.newKey("stub-key"), stub.cognitoIssuer());

		HttpResponse<String> callback = flow.followHostedUi(authorization, code);

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_id_token");
		assertThat(userIdOf(sub)).isNull();
	}

}
