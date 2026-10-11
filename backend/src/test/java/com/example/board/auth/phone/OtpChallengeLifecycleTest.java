package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.MutableClock;
import com.example.board.support.PhoneClient;

/** TASK-7 B1 OTP 는 3분 후 만료되고, 한 번 성공하면 소멸한다(재사용 불가). 서비스 수준에서 시계를 움직여 확인한다. */
@IntegrationTest
class OtpChallengeLifecycleTest {

	@LocalServerPort
	int port;

	@Autowired
	PhoneOtpService otp;

	@Autowired
	MutableClock clock;

	@Autowired
	SmsSender sms;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private PhoneClient phone() {
		return new PhoneClient(new Api(port), sms);
	}

	@Test
	@DisplayName("TASK-7#B1 3분 안에는 맞는 코드로 검증되고 번호(E.164)를 돌려준다")
	void validWithinTtl() {
		PhoneClient phone = phone();
		String number = PhoneClient.uniquePhone();
		UUID id = UUID.fromString(phone.challenge(number));

		clock.advance(Duration.ofMinutes(3).minusSeconds(1));

		assertThat(otp.verify(id, phone.lastCode(number))).hasValue(PhoneClient.e164(number));
	}

	@Test
	@DisplayName("TASK-7#B1 3분이 지나면 맞는 코드여도 실패한다")
	void expiresAfterThreeMinutes() {
		PhoneClient phone = phone();
		String number = PhoneClient.uniquePhone();
		UUID id = UUID.fromString(phone.challenge(number));

		clock.advance(Duration.ofMinutes(3));

		assertThat(otp.verify(id, phone.lastCode(number))).isEmpty();
	}

	@Test
	@DisplayName("TASK-7#B1 한 번 성공한 챌린지는 소멸한다: 같은 코드로 다시 검증하면 실패(재사용 불가)")
	void consumedAfterSuccess() {
		PhoneClient phone = phone();
		String number = PhoneClient.uniquePhone();
		UUID id = UUID.fromString(phone.challenge(number));
		String code = phone.lastCode(number);

		assertThat(otp.verify(id, code)).isPresent();
		assertThat(otp.verify(id, code)).isEmpty();
	}

	@Test
	@DisplayName("TASK-7#B1 없는 챌린지 id 는 실패")
	void unknownChallenge() {
		assertThat(otp.verify(UUID.randomUUID(), "123456")).isEmpty();
	}

}
