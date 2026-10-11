package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
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

/** TASK-7#2 POST /api/auth/phone/otp: 6자리 OTP 생성(해시만 저장, 만료 3분) + SMS 발송, 계정 존재 여부 비노출. */
@IntegrationTest
class PhoneOtpSendTest {

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

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		phone = new PhoneClient(api, sms);
	}

	@Test
	@DisplayName("TASK-7#2 202 {challengeId, expiresIn=180} + 그 번호(E.164)로 6자리 인증번호 SMS")
	void sendsOtp() {
		String number = PhoneClient.uniquePhone();

		HttpResponse<String> response = phone.sendOtp(number);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		JsonNode body = AuthClient.json(response);
		assertThat(UUID.fromString(body.get("challengeId").asString())).isNotNull();
		assertThat(body.get("expiresIn").asLong()).isEqualTo(180);
		assertThat(phone.lastCode(number)).matches("\\d{6}");
		assertThat(phone.messageCount(number)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-7#2 otp_challenges 에는 해시만 저장한다: phone=E.164, purpose=LOGIN, attempts=0, 만료=생성+3분, 요청 IP")
	void storesOnlyHash() {
		String number = PhoneClient.uniquePhone();
		String challengeId = phone.challenge(number);
		String code = phone.lastCode(number);

		Map<String, Object> row = jdbc.queryForMap("select * from otp_challenges where id = ?",
				UUID.fromString(challengeId));

		assertThat(row.get("phone")).isEqualTo(PhoneClient.e164(number));
		assertThat((String) row.get("code_hash")).matches("[0-9a-f]{64}").doesNotContain(code);
		assertThat(row.get("purpose")).isEqualTo("LOGIN");
		assertThat(row.get("attempts")).isEqualTo(0);
		assertThat(row.get("consumed_at")).isNull();
		assertThat(row.get("requester_ip")).isNotNull();
		Duration ttl = Duration.between(((Timestamp) row.get("created_at")).toInstant(),
				((Timestamp) row.get("expires_at")).toInstant());
		assertThat(ttl).isEqualTo(Duration.ofMinutes(3));
		// 원문 코드는 DB 어디에도 없다
		assertThat(jdbc.queryForObject("select count(*) from otp_challenges where code_hash = ? or phone = ?",
				Long.class, code, code)).isZero();
	}

	@Test
	@DisplayName("TASK-7#2 계정이 있는 번호와 없는 번호의 응답은 상태·필드가 같다(계정 존재 비노출)")
	void doesNotRevealAccount() {
		String existing = PhoneClient.uniquePhone();
		User user = fixtures.user();
		identities.saveAndFlush(UserIdentity.link(user, AuthProvider.PHONE, PhoneClient.e164(existing), null));

		HttpResponse<String> known = phone.sendOtp(existing);
		HttpResponse<String> unknown = phone.sendOtp(PhoneClient.uniquePhone());

		assertThat(known.statusCode()).isEqualTo(202);
		assertThat(unknown.statusCode()).isEqualTo(202);
		assertThat(AuthClient.json(known).propertyNames()).containsExactlyElementsOf(AuthClient.json(unknown).propertyNames());
		assertThat(AuthClient.json(known).propertyNames()).containsExactlyInAnyOrder("challengeId", "expiresIn");
	}

	@Test
	@DisplayName("TASK-7#2 공개 엔드포인트지만 세션 경로라 CSRF 토큰이 없으면 403")
	void requiresCsrf() {
		HttpResponse<String> response = phone.withIp(api.post("/auth/phone/otp"))
			.json(PhoneClient.otpBody(PhoneClient.uniquePhone()))
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
	}

	@Test
	@DisplayName("TASK-7#2 잘못된 번호는 400 invalid_phone, 빈 값은 400 validation, 둘 다 SMS 를 보내지 않는다")
	void rejectsInvalidPhone() {
		HttpResponse<String> invalid = phone.sendOtp("010-12");
		HttpResponse<String> blank = phone.sendOtp(" ");

		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(invalid.body()).contains("\"error\":\"invalid_phone\"");
		assertThat(blank.statusCode()).isEqualTo(400);
		assertThat(blank.body()).contains("\"error\":\"validation\"");
	}

}
