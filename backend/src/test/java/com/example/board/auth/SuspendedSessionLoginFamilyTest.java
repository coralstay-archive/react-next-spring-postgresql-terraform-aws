package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.board.auth.phone.PhoneAuthController;
import com.example.board.auth.phone.PhoneVerifyRequest;
import com.example.board.common.ApiException;

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

/**
 * TASK-24#4 (BE-18) 정지 계정의 세션·토큰이 남은 클라이언트도 로그인 계열(로그인·가입·refresh·OTP 발송·검증)은 정지 필터에 막히지 않고 각
 * 엔드포인트의 계약대로 응답한다. 현재 계정을 바꾸는 쓰기(OTP 연결 {@code ?link=true} 등)는 여전히 403 suspended.
 */
@IntegrationTest
class SuspendedSessionLoginFamilyTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	PhoneAuthController phoneAuth;

	Api api;

	AuthClient auth;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		phone = new PhoneClient(api, sms);
	}

	private void suspend(String email) {
		jdbc.update("""
				update users set status = 'SUSPENDED'
				where id = (select user_id from user_identities where provider = 'LOCAL' and subject = ?)""", email);
	}

	/** 로그인한 뒤 DB 에서만 정지해 세션이 남은 상태(정지 API 는 세션을 지우므로 직접 바꾼다). */
	private String suspendedSession(String email) {
		String session = auth.sessionCookie(email);
		suspend(email);
		return session;
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	@Test
	@DisplayName("TASK-24#4 정지 계정의 세션이 남은 브라우저에서 다른 계정으로 로그인·가입·OTP 발송을 할 수 있다")
	void otherAccountLoginFamilyWorks() {
		String session = suspendedSession(auth.signedUp());
		String other = auth.signedUp();

		HttpResponse<String> login = auth.withIp(api.post("/auth/login"))
			.cookie(session)
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(other, AuthClient.PASSWORD))
			.send();
		HttpResponse<String> signup = auth.withIp(api.post("/auth/signup"))
			.cookie(session)
			.csrf(auth.csrfToken())
			.json(AuthClient.signupBody(AuthClient.uniqueEmail(), AuthClient.PASSWORD, "newbie"))
			.send();
		HttpResponse<String> otp = phone.withIp(api.post("/auth/phone/otp"))
			.cookie(session)
			.csrf(auth.csrfToken())
			.json(PhoneClient.otpBody(PhoneClient.uniquePhone()))
			.send();

		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		assertThat(signup.statusCode()).as(signup.body()).isEqualTo(201);
		assertThat(otp.statusCode()).as(otp.body()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-24#4 정지 계정의 세션으로 OTP 로그인(검증)도 된다 — 새 번호면 새 계정")
	void otpVerifyWorks() {
		String session = suspendedSession(auth.signedUp());
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify("", challenge, phone.lastCode(number), session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-24#4 정지 계정 자신으로 다시 로그인하면 필터의 쓰기 오류가 아니라 로그인 계약: 틀린 비밀번호 401, 맞으면 403 suspended(로그인 문구)")
	void sameSuspendedAccountGetsLoginContract() {
		String email = auth.signedUp();
		String session = suspendedSession(email);

		HttpResponse<String> wrong = auth.withIp(api.post("/auth/login"))
			.cookie(session)
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(email, "wrong-password-1"))
			.send();
		HttpResponse<String> right = auth.withIp(api.post("/auth/login"))
			.cookie(session)
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(email, AuthClient.PASSWORD))
			.send();

		assertThat(wrong.statusCode()).as(wrong.body()).isEqualTo(401);
		assertThat(error(wrong)).isEqualTo("invalid_credentials");
		assertThat(right.statusCode()).as(right.body()).isEqualTo(403);
		assertThat(AuthClient.json(right).get("message").asString()).isEqualTo("정지된 계정은 로그인할 수 없습니다");
	}

	@Test
	@DisplayName("TASK-24#4 정지 계정의 access 토큰을 붙인 채 다른 계정의 refresh·JWT 로그인을 할 수 있다")
	void suspendedBearerCanRefreshAndLoginOthers() {
		String suspendedEmail = auth.signedUp();
		String suspendedAccess = auth.tokens(suspendedEmail).get("accessToken").asString();
		suspend(suspendedEmail);
		String other = auth.signedUp();
		String otherRefresh = auth.tokens(other).get("refreshToken").asString();

		HttpResponse<String> refresh = api.post("/auth/token/refresh")
			.bearer(suspendedAccess)
			.json("{\"refreshToken\":" + AuthClient.quote(otherRefresh) + "}")
			.send();
		HttpResponse<String> login = auth.withIp(api.post("/auth/login?token=jwt"))
			.bearer(suspendedAccess)
			.json(AuthClient.loginBody(other, AuthClient.PASSWORD))
			.send();

		assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-24#4 현재(정지) 계정에 번호를 붙이는 OTP 연결(link=true)은 여전히 403 suspended")
	void linkStaysBlocked() {
		String session = suspendedSession(auth.signedUp());
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify("?link=true", challenge, phone.lastCode(number), session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
		assertThat(error(response)).isEqualTo("suspended");
	}


	private int phoneIdentities(String number) {
		return jdbc.queryForObject("select count(*) from user_identities where provider = 'PHONE' and subject = ?",
				Integer.class, PhoneClient.e164(number));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "?link=1", "?link=on", "?link=yes", "?link=%74rue", "?link=+true", "?link=TRUE%20" })
	@DisplayName("TASK-24#4 link 를 Spring boolean 변환이 참으로 읽는 다른 표기(1·on·yes·퍼센트 인코딩·공백)도 정지 필터 예외가 아니다 — 403 suspended, 번호는 연결되지 않는다")
	void linkVariantsStayBlocked(String query) {
		String session = suspendedSession(auth.signedUp());
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify(query, challenge, phone.lastCode(number), session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
		assertThat(error(response)).isEqualTo("suspended");
		assertThat(phoneIdentities(number)).isZero();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "?link=true", "?link=1", "?link=%74rue" })
	@DisplayName("TASK-24#4 ACTIVE 계정의 연결은 그대로 된다(link 표기는 컨트롤러의 boolean 변환대로)")
	void activeLinkStillWorks(String query) {
		String session = auth.sessionCookie(auth.signedUp());
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);

		HttpResponse<String> response = phone.verify(query, challenge, phone.lastCode(number), session);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(phoneIdentities(number)).isOne();
	}

	@Test
	@DisplayName("TASK-24#4 심층 방어: 필터를 지나더라도 연결 모드는 ACTIVE 가 아닌 principal 을 403 suspended 로 거절한다(챌린지 검증 전)")
	void linkRejectsSuspendedPrincipal() {
		AuthUser suspended = new AuthUser(1L, Role.USER, UserStatus.SUSPENDED);
		PhoneVerifyRequest body = new PhoneVerifyRequest(UUID.randomUUID().toString(), "000000");

		assertThatThrownBy(() -> phoneAuth.verify(body, null, true, suspended, new MockHttpServletRequest(),
				new MockHttpServletResponse()))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				assertThat(ex.getError()).isEqualTo("suspended");
			});
	}

}
