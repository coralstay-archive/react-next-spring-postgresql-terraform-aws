package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;

/**
 * TASK-22#4 (doc-2 BE-25) SMS 발송 허용 국가(app.sms.allowed-regions, 기본 KR)와 휴대폰 유형만. 그 밖의 번호는 발송 전에 400
 * {@code phone_not_allowed} — 챌린지·SMS 를 만들지 않고 발송 한도도 소모하지 않는다(B3).
 */
@IntegrationTest
class PhoneSendRegionTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		phone = new PhoneClient(new Api(port), sms);
	}

	private long challengesFromIp() {
		return jdbc.queryForObject("select count(*) from otp_challenges where requester_ip = ?", Long.class, phone.ip());
	}

	@Test
	@DisplayName("TASK-22#B3 허용 국가가 아닌 번호(+1·+44)는 발송 전에 400 phone_not_allowed — SMS·챌린지 없음")
	void otherRegionIsRejectedBeforeSending() {
		for (String number : new String[] { "+1 650-253-0000", "+44 7400 123456" }) {
			HttpResponse<String> response = phone.sendOtp(number);

			assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
			assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("phone_not_allowed");
		}
		assertThat(phone.messageCount("+16502530000")).isZero();
		assertThat(phone.messageCount("+447400123456")).isZero();
		assertThat(challengesFromIp()).isZero();
	}

	@Test
	@DisplayName("TASK-22#4 국내 유선 번호(02-)는 400 phone_not_allowed, 국내 휴대폰은 202")
	void landlineIsRejectedMobileIsSent() {
		HttpResponse<String> landline = phone.sendOtp("02-312-3456");
		assertThat(landline.statusCode()).as(landline.body()).isEqualTo(400);
		assertThat(AuthClient.json(landline).get("error").asString()).isEqualTo("phone_not_allowed");

		assertThat(phone.sendOtp(PhoneClient.uniquePhone()).statusCode()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-22#B3 거절된 번호는 IP 발송 한도(1시간 20회)를 소모하지 않는다 — 25번 거절된 뒤에도 같은 IP 로 보낼 수 있다")
	void rejectedNumbersDoNotConsumeRateLimit() {
		for (int i = 0; i < 25; i++) {
			assertThat(phone.sendOtp("+1 650-253-0000").statusCode()).isEqualTo(400);
		}

		assertThat(phone.sendOtp(PhoneClient.uniquePhone()).statusCode()).isEqualTo(202);
	}

}
