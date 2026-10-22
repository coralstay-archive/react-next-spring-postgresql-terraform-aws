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
import com.example.board.support.AuthFixtures;
import com.example.board.support.FailingInsert;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;

/**
 * TASK-25#5 BE-22 인증번호가 맞아 챌린지를 소모한 뒤 계정 처리(가입·연결·토큰 발급)가 실패하면, 사용자는 기존 오류 응답(5xx)을 받고 챌린지는
 * 소모되지 않은 상태로 돌아간다 → 같은 인증번호로 다시 확인할 수 있다(새 발송·쿨다운·일일 한도를 쓰지 않는다).
 */
@IntegrationTest
class PhoneVerifyRecoveryTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		phone = new PhoneClient(api, sms);
	}

	private boolean consumed(String challengeId) {
		return jdbc.queryForObject("select consumed_at is not null from otp_challenges where id = ?::uuid",
				Boolean.class, challengeId);
	}

	private int phoneIdentities(String e164) {
		return jdbc.queryForObject("select count(*) from user_identities where provider = 'PHONE' and subject = ?",
				Integer.class, e164);
	}

	@Test
	@DisplayName("TASK-25#5 세션 로그인: 가입 중 DB 오류면 5xx JSON, 챌린지는 소모되지 않고 같은 코드로 다시 확인하면 로그인된다(SMS 추가 발송 없음)")
	void sessionLoginRecovers() {
		String number = PhoneClient.uniquePhone();
		String e164 = PhoneClient.e164(number);
		String challengeId = phone.challenge(number);
		String code = phone.lastCode(number);

		HttpResponse<String> failed;
		try (FailingInsert ignored = FailingInsert.on(jdbc, "user_identities", "subject", e164)) {
			failed = phone.verify(challengeId, code);
		}

		assertThat(failed.statusCode()).isGreaterThanOrEqualTo(500);
		assertThat(AuthClient.json(failed).get("error").asString()).isNotBlank();
		assertThat(consumed(challengeId)).as("실패한 계정 처리 뒤 챌린지는 다시 쓸 수 있다").isFalse();
		assertThat(phoneIdentities(e164)).isZero();

		HttpResponse<String> retried = phone.verify(challengeId, code);

		assertThat(retried.statusCode()).isEqualTo(200);
		assertThat(consumed(challengeId)).isTrue();
		assertThat(phoneIdentities(e164)).isEqualTo(1);
		assertThat(phone.messageCount(number)).as("재시도에 새 발송이 필요 없다").isEqualTo(1);
		assertThat(phone.verify(challengeId, code).statusCode()).as("성공한 뒤에는 여전히 재사용 불가").isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-25#5 ?token=jwt: 계정 처리 실패 뒤 같은 코드로 다시 확인하면 토큰이 발급된다")
	void tokenLoginRecovers() {
		String number = PhoneClient.uniquePhone();
		String e164 = PhoneClient.e164(number);
		String challengeId = phone.challenge(number);
		String code = phone.lastCode(number);

		HttpResponse<String> failed;
		try (FailingInsert ignored = FailingInsert.on(jdbc, "user_identities", "subject", e164)) {
			failed = phone.verify("?token=jwt", challengeId, code, null);
		}
		assertThat(failed.statusCode()).isGreaterThanOrEqualTo(500);

		HttpResponse<String> retried = phone.verify("?token=jwt", challengeId, code, null);

		assertThat(retried.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(retried).get("accessToken").asString()).isNotBlank();
	}

	@Test
	@DisplayName("TASK-25#5 ?link=true: 연결 중 DB 오류 뒤 같은 코드로 다시 확인하면 연결된다")
	void linkRecovers() {
		String session = fixtures.sessionCookie(fixtures.user());
		String number = PhoneClient.uniquePhone();
		String e164 = PhoneClient.e164(number);
		String challengeId = phone.challenge(number);
		String code = phone.lastCode(number);

		HttpResponse<String> failed;
		try (FailingInsert ignored = FailingInsert.on(jdbc, "user_identities", "subject", e164)) {
			failed = phone.verify("?link=true", challengeId, code, session);
		}
		assertThat(failed.statusCode()).isGreaterThanOrEqualTo(500);
		assertThat(consumed(challengeId)).isFalse();

		HttpResponse<String> retried = phone.verify("?link=true", challengeId, code, session);

		assertThat(retried.statusCode()).isEqualTo(200);
		assertThat(phoneIdentities(e164)).isEqualTo(1);
	}

}
