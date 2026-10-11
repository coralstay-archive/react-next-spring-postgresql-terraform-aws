package com.example.board.auth.phone;

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

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;

import tools.jackson.databind.JsonNode;

/**
 * TASK-7#3 POST /api/auth/phone/verify: 코드가 맞으면 (PHONE, E.164) identity 로 로그인하거나 새 계정을 만든다. 기본은 웹 세션,
 * {@code ?token=jwt} 면 access+refresh. {@code ?link=true} 면 로그인한 계정에 PHONE identity 를 연결한다.
 */
@IntegrationTest
class PhoneVerifyTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserIdentityRepository identities;

	Api api;

	AuthClient auth;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		phone = new PhoneClient(api, sms);
	}

	private Long phoneOwner(String e164) {
		List<Long> ids = jdbc.queryForList(
				"select user_id from user_identities where provider = 'PHONE' and subject = ?", Long.class, e164);
		return ids.isEmpty() ? null : ids.getFirst();
	}

	private long localUserId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	private User phoneUser(String e164) {
		User user = fixtures.user();
		identities.saveAndFlush(UserIdentity.link(user, AuthProvider.PHONE, e164, null));
		return user;
	}

	@Test
	@DisplayName("TASK-7#3 처음 보는 번호: 새 계정 + PHONE identity(subject=E.164) + users.phone, 세션 로그인(200 + SESSION)")
	void signsUpNewPhone() {
		String number = PhoneClient.uniquePhone();
		String e164 = PhoneClient.e164(number);
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify(challenge, phone.lastCode(number));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		Long owner = phoneOwner(e164);
		assertThat(owner).isNotNull();
		assertThat(body.get("id").asLong()).isEqualTo(owner);
		assertThat(body.get("providers").toString()).isEqualTo("[\"PHONE\"]");
		assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, owner)).isEqualTo(e164);
		String session = "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();
		HttpResponse<String> me = api.get("/auth/me").cookie(session).send();
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(me).get("id").asLong()).isEqualTo(owner);
	}

	@Test
	@DisplayName("TASK-7#3 이미 있는 PHONE identity 면 그 계정으로 로그인한다(새 계정을 만들지 않는다)")
	void logsIntoExistingAccount() {
		String number = PhoneClient.uniquePhone();
		User user = phoneUser(PhoneClient.e164(number));
		long before = jdbc.queryForObject("select count(*) from users", Long.class);

		HttpResponse<String> response = phone.verify(phone.challenge(number), phone.lastCode(number));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("id").asLong()).isEqualTo(user.getId());
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isEqualTo(before);
	}

	@Test
	@DisplayName("TASK-7#3 ?token=jwt: CSRF 없이 access+refresh 발급, 세션 쿠키 없음, Bearer 로 /auth/me")
	void issuesTokens() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/verify?token=jwt"))
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode tokens = AuthClient.json(response);
		assertThat(tokens.get("tokenType").asString()).isEqualTo("Bearer");
		assertThat(tokens.get("refreshToken").asString()).isNotBlank();
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
		HttpResponse<String> me = api.get("/auth/me").bearer(tokens.get("accessToken").asString()).send();
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(me).get("id").asLong()).isEqualTo(phoneOwner(PhoneClient.e164(number)));
		assertThat(auth.refresh(tokens.get("refreshToken").asString()).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#3 틀린 코드는 401 invalid_otp 이고 시도 횟수가 오른다. 세션·계정은 만들지 않는다")
	void wrongCode() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify(challenge, PhoneClient.wrong(phone.lastCode(number)));

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"error\":\"invalid_otp\"");
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
		assertThat(jdbc.queryForObject("select attempts from otp_challenges where id = ?", Integer.class,
				UUID.fromString(challenge))).isEqualTo(1);
		assertThat(phoneOwner(PhoneClient.e164(number))).isNull();
	}

	@Test
	@DisplayName("TASK-7#3 없는 챌린지·형식이 틀린 id 도 같은 401 invalid_otp, 빈 코드는 400")
	void unknownChallenge() {
		HttpResponse<String> unknown = phone.verify(UUID.randomUUID().toString(), "123456");
		HttpResponse<String> malformed = phone.verify("not-a-uuid", "123456");
		HttpResponse<String> blank = phone.verify(UUID.randomUUID().toString(), "");

		assertThat(unknown.statusCode()).isEqualTo(401);
		assertThat(unknown.body()).contains("\"error\":\"invalid_otp\"");
		assertThat(malformed.statusCode()).isEqualTo(401);
		assertThat(malformed.body()).contains("\"error\":\"invalid_otp\"");
		assertThat(blank.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-7#3 정지된 계정은 코드가 맞아도 403 suspended (세션·토큰 없음)")
	void suspendedAccount() {
		String number = PhoneClient.uniquePhone();
		User user = phoneUser(PhoneClient.e164(number));
		jdbc.update("update users set status = 'SUSPENDED' where id = ?", user.getId());

		HttpResponse<String> session = phone.verify(phone.challenge(number), phone.lastCode(number));
		phone.ip(PhoneClient.uniqueIp());
		HttpResponse<String> jwt = phone.verify("?token=jwt", phoneChallengeAfterCooldown(number),
				phone.lastCode(number), null);

		assertThat(session.statusCode()).isEqualTo(403);
		assertThat(session.body()).contains("\"error\":\"suspended\"");
		assertThat(Api.setCookie(session, "SESSION")).isEmpty();
		assertThat(jwt.statusCode()).isEqualTo(403);
		assertThat(jwt.body()).doesNotContain("accessToken");
	}

	/** 같은 번호로 바로 다시 보내면 1분 제한(TASK-7#4)에 걸리므로 DB 의 직전 챌린지 시각을 당겨 둔다. */
	private String phoneChallengeAfterCooldown(String number) {
		jdbc.update("update otp_challenges set created_at = created_at - interval '2 minutes' where phone = ?",
				PhoneClient.e164(number));
		return phone.challenge(number);
	}

	@Test
	@DisplayName("TASK-7#3 세션 경로 검증은 CSRF 토큰이 없으면 403 (로그인 CSRF 방어)")
	void sessionVerifyRequiresCsrf() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/verify"))
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
	}

	@Test
	@DisplayName("TASK-7#3 연결(?link=true)은 세션 자격을 쓰므로 ?token=jwt 가 같이 있어도 CSRF 면제가 아니다")
	void linkIsNeverCsrfExempt() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/verify?token=jwt&link=true"))
			.cookie(session)
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
		assertThat(phoneOwner(PhoneClient.e164(number))).isNull();
	}

	@Test
	@DisplayName("TASK-7#3 ?link=true: 로그인한(세션) 계정에 PHONE identity 를 붙이고 users.phone 을 채운다. 세션 사용자는 그대로다")
	void linksToCurrentAccount() {
		String email = auth.signedUp();
		long userId = localUserId(email);
		String session = auth.sessionCookie(email);
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify("?link=true", challenge, phone.lastCode(number), session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("id").asLong()).isEqualTo(userId);
		assertThat(AuthClient.json(response).get("providers").toString()).isEqualTo("[\"LOCAL\",\"PHONE\"]");
		assertThat(phoneOwner(PhoneClient.e164(number))).isEqualTo(userId);
		assertThat(jdbc.queryForObject("select phone from users where id = ?", String.class, userId))
			.isEqualTo(PhoneClient.e164(number));
		HttpResponse<String> me = api.get("/auth/me").cookie(session).send();
		assertThat(AuthClient.json(me).get("id").asLong()).isEqualTo(userId);
	}

	@Test
	@DisplayName("TASK-7#3 ?link=true 는 Bearer 로도 된다")
	void linksWithBearer() {
		String email = auth.signedUp();
		String access = auth.tokens(email).get("accessToken").asString();
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/verify?link=true"))
			.bearer(access)
			.json(PhoneClient.verifyBody(challenge, phone.lastCode(number)))
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(phoneOwner(PhoneClient.e164(number))).isEqualTo(localUserId(email));
	}

	@Test
	@DisplayName("TASK-7#3 ?link=true 로 다른 계정의 번호를 연결하면 409 identity_in_use (소유자는 그대로)")
	void linkingOthersPhoneConflicts() {
		String number = PhoneClient.uniquePhone();
		User owner = phoneUser(PhoneClient.e164(number));
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);

		HttpResponse<String> response = phone.verify("?link=true", phone.challenge(number), phone.lastCode(number),
				session);

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.body()).contains("\"error\":\"identity_in_use\"");
		assertThat(phoneOwner(PhoneClient.e164(number))).isEqualTo(owner.getId());
	}

	@Test
	@DisplayName("TASK-7#3 ?link=true 인데 로그인하지 않았으면 401 이고 챌린지는 소모하지 않는다")
	void linkRequiresLogin() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);
		String code = phone.lastCode(number);

		HttpResponse<String> response = phone.verify("?link=true", challenge, code, null);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(phone.verify(challenge, code).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#3 token 파라미터는 jwt 만, link 와 token=jwt 는 같이 쓸 수 없다(400)")
	void badParameters() {
		String email = auth.signedUp();
		String access = auth.tokens(email).get("accessToken").asString();

		HttpResponse<String> badToken = phone.verify("?token=cookie", UUID.randomUUID().toString(), "123456", null);
		HttpResponse<String> linkWithToken = phone.withIp(api.post("/auth/phone/verify?link=true&token=jwt"))
			.bearer(access)
			.json(PhoneClient.verifyBody(UUID.randomUUID().toString(), "123456"))
			.send();

		assertThat(badToken.statusCode()).isEqualTo(400);
		assertThat(linkWithToken.statusCode()).isEqualTo(400);
	}

}
