package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GoogleUser;
import com.example.board.support.PhoneClient;

import tools.jackson.databind.JsonNode;

/**
 * TASK-7#5 재활용 번호 보호. B4: 다른 identity(LOCAL·소셜·Cognito)도 있는 계정에 PHONE 으로 로그인하면, 기존 수단으로 다시 인증하기
 * 전까지 제한 세션·토큰이다 — 읽기는 되고 쓰기는 로그아웃·step-up 외에 403 {@code step_up_required}.
 */
@IntegrationTest
class PhoneStepUpTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JwtTokenService jwt;

	Api api;

	AuthClient auth;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		phone = new PhoneClient(api, sms);
	}

	private long localUserId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	/** 계정에 PHONE identity 를 직접 붙이고 그 번호(국내 표기)를 돌려준다. */
	private String attachPhone(long userId) {
		String number = PhoneClient.uniquePhone();
		jdbc.update("insert into user_identities (user_id, provider, subject) values (?, 'PHONE', ?)", userId,
				PhoneClient.e164(number));
		return number;
	}

	private HttpResponse<String> phoneSessionLogin(String number) {
		return phone.verify(phone.challenge(number), phone.lastCode(number));
	}

	private static String session(HttpResponse<String> response) {
		return "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();
	}

	private JsonNode me(Api.Request request) {
		HttpResponse<String> response = request.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private HttpResponse<String> write(String session) {
		return api.post("/test/echo").cookie(session).csrf(auth.csrfToken()).send();
	}

	@Test
	@DisplayName("TASK-7#B4 LOCAL 도 있는 계정에 PHONE 으로 로그인하면 제한 세션: 읽기 200, 쓰기 403 step_up_required, me 에 stepUpRequired·사용 가능한 수단")
	void restrictedSession() {
		String email = auth.signedUp();
		String number = attachPhone(localUserId(email));

		HttpResponse<String> login = phoneSessionLogin(number);
		String session = session(login);

		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(login).get("stepUpRequired").asBoolean()).isTrue();
		JsonNode me = me(api.get("/auth/me").cookie(session));
		assertThat(me.get("stepUpRequired").asBoolean()).isTrue();
		assertThat(me.get("stepUpProviders").toString()).isEqualTo("[\"LOCAL\"]");
		assertThat(api.get("/test/whoami").cookie(session).send().statusCode()).isEqualTo(200);
		HttpResponse<String> write = write(session);
		assertThat(write.statusCode()).isEqualTo(403);
		assertThat(write.body()).contains("\"error\":\"step_up_required\"");
	}

	@Test
	@DisplayName("TASK-7#B4 PHONE 만 있는 계정은 제한하지 않는다(stepUpRequired=false, 쓰기 가능)")
	void phoneOnlyAccountIsNotRestricted() {
		String number = PhoneClient.uniquePhone();
		HttpResponse<String> login = phoneSessionLogin(number);
		String session = session(login);

		assertThat(AuthClient.json(login).get("stepUpRequired").asBoolean()).isFalse();
		JsonNode me = me(api.get("/auth/me").cookie(session));
		assertThat(me.get("stepUpRequired").asBoolean()).isFalse();
		assertThat(me.get("stepUpProviders").isEmpty()).isTrue();
		assertThat(write(session).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#B4 일반 LOCAL 로그인은 제한이 없다(stepUpRequired=false)")
	void localLoginIsNotRestricted() {
		String email = auth.signedUp();
		attachPhone(localUserId(email));
		String session = auth.sessionCookie(email);

		assertThat(me(api.get("/auth/me").cookie(session)).get("stepUpRequired").asBoolean()).isFalse();
		assertThat(write(session).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#B4 ?token=jwt 도 제한 토큰이다: step_up_required 클레임, Bearer 쓰기 403, refresh 로 받은 토큰도 제한 유지")
	void restrictedTokens() {
		String email = auth.signedUp();
		String number = attachPhone(localUserId(email));
		String challenge = phone.challenge(number);
		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/verify?token=jwt"))
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send();
		JsonNode tokens = AuthClient.json(response);
		String access = tokens.get("accessToken").asString();

		assertThat(jwt.decode(access).getClaimAsBoolean(JwtTokenService.CLAIM_STEP_UP_REQUIRED)).isTrue();
		assertThat(me(api.get("/auth/me").bearer(access)).get("stepUpRequired").asBoolean()).isTrue();
		HttpResponse<String> write = api.post("/test/echo").bearer(access).send();
		assertThat(write.statusCode()).isEqualTo(403);
		assertThat(write.body()).contains("\"error\":\"step_up_required\"");

		HttpResponse<String> refreshed = auth.refresh(tokens.get("refreshToken").asString());
		String refreshedAccess = AuthClient.json(refreshed).get("accessToken").asString();
		assertThat(jwt.decode(refreshedAccess).getClaimAsBoolean(JwtTokenService.CLAIM_STEP_UP_REQUIRED)).isTrue();
		assertThat(api.post("/test/echo").bearer(refreshedAccess).send().statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-7#5 제한 세션도 로그아웃은 된다(204)")
	void restrictedCanLogOut() {
		String email = auth.signedUp();
		String session = session(phoneSessionLogin(attachPhone(localUserId(email))));

		HttpResponse<String> logout = api.post("/auth/logout").cookie(session).csrf(auth.csrfToken()).send();

		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(api.get("/auth/me").cookie(session).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-7#5 POST /auth/step-up/local {password}: 맞으면 세션 ID 를 바꾼 완전한 세션(쓰기 가능), 이전 세션은 무효")
	void stepUpWithPassword() {
		String email = auth.signedUp();
		String restricted = session(phoneSessionLogin(attachPhone(localUserId(email))));

		HttpResponse<String> stepUp = api.post("/auth/step-up/local")
			.cookie(restricted)
			.csrf(auth.csrfToken())
			.json("{\"password\":" + AuthClient.quote(AuthClient.PASSWORD) + "}")
			.send();

		assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(200);
		assertThat(AuthClient.json(stepUp).get("stepUpRequired").asBoolean()).isFalse();
		String full = session(stepUp);
		assertThat(full).isNotEqualTo(restricted);
		assertThat(me(api.get("/auth/me").cookie(full)).get("stepUpRequired").asBoolean()).isFalse();
		assertThat(write(full).statusCode()).isEqualTo(200);
		assertThat(api.get("/auth/me").cookie(restricted).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-7#5 step-up 비밀번호가 틀리면 401 invalid_credentials 이고 계속 제한 세션이다")
	void stepUpWrongPassword() {
		String email = auth.signedUp();
		String restricted = session(phoneSessionLogin(attachPhone(localUserId(email))));

		HttpResponse<String> stepUp = api.post("/auth/step-up/local")
			.cookie(restricted)
			.csrf(auth.csrfToken())
			.json("{\"password\":\"wr0ng-password\"}")
			.send();

		assertThat(stepUp.statusCode()).isEqualTo(401);
		assertThat(stepUp.body()).contains("\"error\":\"invalid_credentials\"");
		assertThat(write(restricted).statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-7#5 Bearer step-up: 제한 없는 새 토큰을 주고, 본문의 제한 refresh 토큰 family 는 폐기한다")
	void stepUpWithBearer() {
		String email = auth.signedUp();
		String number = attachPhone(localUserId(email));
		String challenge = phone.challenge(number);
		JsonNode restricted = AuthClient.json(phone.withIp(api.post("/auth/phone/verify?token=jwt"))
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send());

		HttpResponse<String> stepUp = api.post("/auth/step-up/local")
			.bearer(restricted.get("accessToken").asString())
			.json("{\"password\":" + AuthClient.quote(AuthClient.PASSWORD) + ",\"refreshToken\":"
					+ AuthClient.quote(restricted.get("refreshToken").asString()) + "}")
			.send();

		assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(200);
		String access = AuthClient.json(stepUp).get("accessToken").asString();
		assertThat(jwt.decode(access).getClaimAsBoolean(JwtTokenService.CLAIM_STEP_UP_REQUIRED)).isFalse();
		assertThat(api.post("/test/echo").bearer(access).send().statusCode()).isEqualTo(200);
		assertThat(auth.refresh(restricted.get("refreshToken").asString()).statusCode()).isEqualTo(401);
		assertThat(auth.refresh(AuthClient.json(stepUp).get("refreshToken").asString()).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#5 LOCAL 이 없는 계정의 step-up/local 은 400 step_up_unavailable")
	void stepUpLocalUnavailable() {
		OAuthFlow flow = new OAuthFlow(api, stub);
		String sub = "g-" + UUID.randomUUID();
		flow.google(new GoogleUser(sub, "stepup@gmail.test", true, "Step Up"));
		long userId = jdbc.queryForObject(
				"select user_id from user_identities where provider = 'GOOGLE' and subject = ?", Long.class, sub);
		String restricted = session(phoneSessionLogin(attachPhone(userId)));

		HttpResponse<String> stepUp = api.post("/auth/step-up/local")
			.cookie(restricted)
			.csrf(auth.csrfToken())
			.json("{\"password\":\"whatever-passw0rd\"}")
			.send();

		assertThat(stepUp.statusCode()).isEqualTo(400);
		assertThat(stepUp.body()).contains("\"error\":\"step_up_unavailable\"");
	}

	@Test
	@DisplayName("TASK-7#5 소셜 계정: 같은 계정의 Google 로 다시 로그인하면(IdentityResolver → 같은 사용자) 제한 없는 새 세션이 된다")
	void stepUpWithSocialLogin() {
		OAuthFlow flow = new OAuthFlow(api, stub);
		String sub = "g-" + UUID.randomUUID();
		GoogleUser google = new GoogleUser(sub, "social-stepup@gmail.test", true, "Social Step");
		flow.google(google);
		long userId = jdbc.queryForObject(
				"select user_id from user_identities where provider = 'GOOGLE' and subject = ?", Long.class, sub);
		String restricted = session(phoneSessionLogin(attachPhone(userId)));
		JsonNode before = me(api.get("/auth/me").cookie(restricted));
		assertThat(before.get("stepUpRequired").asBoolean()).isTrue();
		assertThat(before.get("stepUpProviders").toString()).isEqualTo("[\"GOOGLE\"]");

		HttpResponse<String> callback = flow.session(restricted).google(google);

		assertThat(OAuthFlow.location(callback)).isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok");
		String full = flow.session();
		assertThat(full).isNotEqualTo(restricted);
		JsonNode after = me(api.get("/auth/me").cookie(full));
		assertThat(after.get("id").asLong()).isEqualTo(userId);
		assertThat(after.get("stepUpRequired").asBoolean()).isFalse();
		assertThat(write(full).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#5 제한 세션은 다른 수단을 연결할 수 없다: OAuth 연결 시작·휴대폰 ?link=true 모두 403 step_up_required, 해제(DELETE)도 403")
	void restrictedCannotChangeIdentities() {
		String email = auth.signedUp();
		long userId = localUserId(email);
		String restricted = session(phoneSessionLogin(attachPhone(userId)));
		long identityId = jdbc.queryForObject(
				"select id from user_identities where user_id = ? and provider = 'LOCAL'", Long.class, userId);

		HttpResponse<String> startLink = api.get("/me/identities/link/google").cookie(restricted).send();
		String other = PhoneClient.uniquePhone();
		HttpResponse<String> phoneLink = phone.verify("?link=true", phone.challenge(other), phone.lastCode(other),
				restricted);
		HttpResponse<String> unlink = api.delete("/me/identities/" + identityId)
			.cookie(restricted)
			.csrf(auth.csrfToken())
			.send();

		assertThat(startLink.statusCode()).isEqualTo(403);
		assertThat(startLink.body()).contains("\"error\":\"step_up_required\"");
		assertThat(phoneLink.statusCode()).isEqualTo(403);
		assertThat(phoneLink.body()).contains("\"error\":\"step_up_required\"");
		assertThat(unlink.statusCode()).isEqualTo(403);
		assertThat(jdbc.queryForObject("select count(*) from user_identities where user_id = ?", Long.class, userId))
			.isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-7#5 제한 세션에서도 다른 로그인(LOCAL)은 된다 — 성공하면 제한 없는 세션으로 바뀐다")
	void restrictedCanLogInAgain() {
		String email = auth.signedUp();
		String restricted = session(phoneSessionLogin(attachPhone(localUserId(email))));

		HttpResponse<String> login = api.post("/auth/login")
			.cookie(restricted)
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		assertThat(write(session(login)).statusCode()).isEqualTo(200);
	}

}
