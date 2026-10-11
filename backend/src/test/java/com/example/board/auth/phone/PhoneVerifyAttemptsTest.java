package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;

/** TASK-7 B2 검증 시도 5회 초과 시 챌린지 폐기 — 시도 횟수는 행 잠금으로 세므로 동시 요청에도 정확하다. */
@IntegrationTest
class PhoneVerifyAttemptsTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	PhoneOtpService otp;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		phone = new PhoneClient(new Api(port), sms);
	}

	private int attempts(String challenge) {
		return jdbc.queryForObject("select attempts from otp_challenges where id = ?", Integer.class,
				UUID.fromString(challenge));
	}

	@Test
	@DisplayName("TASK-7#B2 틀린 시도 5번 뒤에는 맞는 코드여도 401 invalid_otp (챌린지 폐기)")
	void discardedAfterFiveFailures() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);
		String code = phone.lastCode(number);

		for (int i = 0; i < 5; i++) {
			assertThat(phone.verify(challenge, PhoneClient.wrong(code)).statusCode()).isEqualTo(401);
		}
		HttpResponse<String> response = phone.verify(challenge, code);

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"error\":\"invalid_otp\"");
		assertThat(attempts(challenge)).isEqualTo(5);
	}

	@Test
	@DisplayName("TASK-7#B2 틀린 시도 4번까지는 맞는 코드로 성공한다(경계)")
	void fourFailuresStillAllowed() {
		String number = PhoneClient.uniquePhone();
		String challenge = phone.challenge(number);
		String code = phone.lastCode(number);

		for (int i = 0; i < 4; i++) {
			phone.verify(challenge, PhoneClient.wrong(code));
		}

		assertThat(phone.verify(challenge, code).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-7#B2 동시에 틀린 시도 20번을 보내도 attempts 는 정확히 5에서 멈추고, 그 뒤 맞는 코드도 실패한다")
	void concurrentAttemptsAreCountedExactly() throws Exception {
		String number = PhoneClient.uniquePhone();
		UUID challenge = UUID.fromString(phone.challenge(number));
		String code = phone.lastCode(number);
		String wrong = PhoneClient.wrong(code);

		List<Callable<Boolean>> calls = IntStream.range(0, 20)
			.<Callable<Boolean>>mapToObj(i -> () -> otp.verify(challenge, wrong).isPresent())
			.toList();
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			for (Future<Boolean> result : pool.invokeAll(calls)) {
				assertThat(result.get()).isFalse();
			}
		}

		assertThat(attempts(challenge.toString())).isEqualTo(5);
		assertThat(otp.verify(challenge, code)).isEmpty();
	}

	@Test
	@DisplayName("TASK-7#B2 동시에 맞는 코드로 여러 번 검증해도 성공은 한 번뿐이다")
	void concurrentSuccessOnlyOnce() throws Exception {
		String number = PhoneClient.uniquePhone();
		UUID challenge = UUID.fromString(phone.challenge(number));
		String code = phone.lastCode(number);

		List<Callable<Boolean>> calls = IntStream.range(0, 10)
			.<Callable<Boolean>>mapToObj(i -> () -> otp.verify(challenge, code).isPresent())
			.toList();
		long successes;
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			successes = 0;
			for (Future<Boolean> result : pool.invokeAll(calls)) {
				if (result.get()) {
					successes++;
				}
			}
		}

		assertThat(successes).isEqualTo(1);
	}

}
