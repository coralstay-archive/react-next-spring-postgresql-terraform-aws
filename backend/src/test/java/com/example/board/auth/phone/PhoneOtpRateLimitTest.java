package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.sms.SmsSender;
import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.MutableClock;
import com.example.board.support.PhoneClient;

/**
 * TASK-7#4 발송 레이트리밋(DB 기준이라 인스턴스가 여러 개여도 같다). 번호: 1분 1회·24시간 10회(B3), IP: 1시간 20회.
 * 넘으면 429 {@code rate_limited} + {@code Retry-After}. 시계는 테스트 시계로 움직인다.
 */
@IntegrationTest
class PhoneOtpRateLimitTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	SmsSender sms;

	@Autowired
	MutableClock clock;

	PhoneClient phone;

	@BeforeEach
	void setUp() {
		phone = new PhoneClient(new Api(port), sms);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private static void assertRateLimited(HttpResponse<String> response, long minRetry, long maxRetry) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
		assertThat(response.body()).contains("\"error\":\"rate_limited\"");
		long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(minRetry, maxRetry);
	}

	private long challengesFor(String number) {
		return jdbc.queryForObject("select count(*) from otp_challenges where phone = ?", Long.class,
				PhoneClient.e164(number));
	}

	@Test
	@DisplayName("TASK-7#B3 같은 번호로 1분 안에 다시 보내면 429 + Retry-After(남은 초), SMS·챌린지는 늘지 않는다")
	void phoneCooldown() {
		String number = PhoneClient.uniquePhone();
		phone.challenge(number);
		clock.advance(Duration.ofSeconds(20));

		HttpResponse<String> response = phone.sendOtp(number);

		assertRateLimited(response, 39, 40);
		assertThat(phone.messageCount(number)).isEqualTo(1);
		assertThat(challengesFor(number)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-7#B3 1분이 지나면 같은 번호로 다시 보낼 수 있다")
	void phoneCooldownElapses() {
		String number = PhoneClient.uniquePhone();
		phone.challenge(number);
		clock.advance(Duration.ofSeconds(60));

		assertThat(phone.sendOtp(number).statusCode()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-7#B3 같은 번호는 24시간에 10회까지: 11번째는 429, 첫 발송 24시간 뒤에는 다시 된다")
	void phoneDailyLimit() {
		String number = PhoneClient.uniquePhone();
		for (int i = 0; i < 10; i++) {
			assertThat(phone.sendOtp(number).statusCode()).as("send %d", i + 1).isEqualTo(202);
			clock.advance(Duration.ofSeconds(61));
		}

		HttpResponse<String> eleventh = phone.sendOtp(number);

		// 첫 발송은 10*61초 전 → 24시간 - 610초 뒤에 풀린다
		long expected = Duration.ofHours(24).minusSeconds(610).toSeconds();
		assertRateLimited(eleventh, expected - 1, expected + 1);
		clock.advance(Duration.ofSeconds(expected));
		assertThat(phone.sendOtp(number).statusCode()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-7#4 같은 클라이언트 IP 는 1시간에 20회까지(번호가 달라도): 21번째는 429, 다른 IP 는 된다")
	void ipHourlyLimit() {
		for (int i = 0; i < 20; i++) {
			assertThat(phone.sendOtp(PhoneClient.uniquePhone()).statusCode()).as("send %d", i + 1).isEqualTo(202);
		}
		String number = PhoneClient.uniquePhone();

		HttpResponse<String> limited = phone.sendOtp(number);

		assertRateLimited(limited, 3590, 3600);
		assertThat(phone.messageCount(number)).isZero();
		assertThat(phone.ip(PhoneClient.uniqueIp()).sendOtp(number).statusCode()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-7#4 IP 한도는 1시간이 지나면 풀린다")
	void ipHourlyLimitElapses() {
		for (int i = 0; i < 20; i++) {
			phone.sendOtp(PhoneClient.uniquePhone());
		}
		clock.advance(Duration.ofHours(1));

		assertThat(phone.sendOtp(PhoneClient.uniquePhone()).statusCode()).isEqualTo(202);
	}

	@Test
	@DisplayName("TASK-7#4 요청 IP 는 신뢰 헤더(CloudFront-Viewer-Address)의 IP 로 기록한다")
	void recordsClientIpFromTrustedHeader() {
		String number = PhoneClient.uniquePhone();
		phone.challenge(number);

		assertThat(jdbc.queryForObject("select requester_ip from otp_challenges where phone = ?", String.class,
				PhoneClient.e164(number))).isEqualTo(phone.ip());
	}

	@Test
	@DisplayName("TASK-7#B3 같은 번호로 동시에 10번 보내도 1분 1회 — 하나만 202, 나머지는 429 (DB 잠금으로 직렬화)")
	void concurrentSendsForSamePhone() throws Exception {
		String number = PhoneClient.uniquePhone();
		List<Callable<Integer>> calls = IntStream.range(0, 10)
			.<Callable<Integer>>mapToObj(i -> () -> phone.sendOtp(number).statusCode())
			.toList();
		List<Integer> statuses;
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			statuses = pool.invokeAll(calls).stream().map(PhoneOtpRateLimitTest::get).toList();
		}

		assertThat(statuses).containsOnly(202, 429);
		assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
		assertThat(challengesFor(number)).isEqualTo(1);
	}

	private static Integer get(Future<Integer> future) {
		try {
			return future.get();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
