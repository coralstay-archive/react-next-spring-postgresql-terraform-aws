package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.MutableClock;
import com.example.board.support.IntegrationTest;
import com.example.board.support.PhoneClient;
import com.example.board.user.User;
import com.example.board.user.UserRepository;

/**
 * TASK-22#1 (doc-2 BE-13) 로그인·가입·step-up 실패 횟수 제한. DB(rate_limit_events) 기준 슬라이딩 창이고, 넘으면 429
 * {@code rate_limited} + {@code Retry-After}(초). 기본값: 로그인 계정별 15분 5회 실패·IP별 1시간 30회 실패, 가입 IP별 1시간 10회,
 * step-up 사용자별 15분 5회 실패. 시계는 테스트 시계로 움직이고, 키(이메일·IP·사용자)는 테스트마다 새로 만든다.
 */
@IntegrationTest
class AuthRateLimitTest {

	private static final String WRONG = "wr0ng-password";

	@LocalServerPort
	int port;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	static void assertRateLimited(HttpResponse<String> response, long minRetry, long maxRetry) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
		assertThat(response.body()).contains("\"error\":\"rate_limited\"");
		long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(minRetry, maxRetry);
	}

	/** 매번 다른 IP 에서 틀린 비밀번호로 로그인한다(계정 한도만 보려고). */
	private void failLogins(String email, int times) {
		for (int i = 0; i < times; i++) {
			HttpResponse<String> response = auth.ip(PhoneClient.uniqueIp()).sessionLogin(email, WRONG);
			assertThat(response.statusCode()).as("failure %d: %s", i + 1, response.body()).isEqualTo(401);
		}
	}

	@Test
	@DisplayName("TASK-22#1 같은 계정에 15분 안에 5번 틀리면(IP 가 달라도) 6번째는 맞는 비밀번호여도 429 + Retry-After(약 15분)")
	void loginAccountLockout() {
		String email = auth.signedUp();
		failLogins(email, 5);

		HttpResponse<String> blocked = auth.ip(PhoneClient.uniqueIp()).sessionLogin(email, AuthClient.PASSWORD);

		assertRateLimited(blocked, 880, 900);
		assertThat(Api.cookie(blocked, "SESSION")).isEmpty();
		assertThat(auth.jwtLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#1 계정 차단은 15분이 지나면 풀린다(창 경계)")
	void loginAccountLockoutElapses() {
		String email = auth.signedUp();
		failLogins(email, 5);
		clock.advance(Duration.ofMinutes(15).minusSeconds(30));
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);

		clock.advance(Duration.ofSeconds(31));

		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#1 실패 4번 + 성공은 차단되지 않고, 성공한 로그인은 실패로 세지 않는다")
	void successfulLoginsDoNotCount() {
		String email = auth.signedUp();
		failLogins(email, 4);
		for (int i = 0; i < 5; i++) {
			assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).as("login %d", i + 1).isEqualTo(200);
		}

		assertThat(auth.sessionLogin(email, WRONG).statusCode()).isEqualTo(401);
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#B2 차단 응답은 계정이 있든 없든 같다(상태·본문·Retry-After 꼴) — 없는 이메일도 5번 틀리면 429")
	void lockoutDoesNotRevealAccountExistence() {
		String existing = auth.signedUp();
		String missing = AuthClient.uniqueEmail();
		failLogins(existing, 5);
		failLogins(missing, 5);

		HttpResponse<String> forExisting = auth.ip(PhoneClient.uniqueIp()).sessionLogin(existing, WRONG);
		HttpResponse<String> forMissing = auth.ip(PhoneClient.uniqueIp()).sessionLogin(missing, WRONG);

		assertRateLimited(forExisting, 880, 900);
		assertRateLimited(forMissing, 880, 900);
		assertThat(forMissing.body()).isEqualTo(forExisting.body());
		assertThat(forMissing.headers().firstValue("Content-Type"))
			.isEqualTo(forExisting.headers().firstValue("Content-Type"));
	}

	@Test
	@DisplayName("TASK-22#1 이메일은 정규화해서 센다(대소문자·공백을 바꿔도 같은 계정)")
	void accountKeyIsNormalizedEmail() {
		String email = auth.signedUp();
		failLogins("  " + email.toUpperCase() + " ", 5);

		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#1 같은 IP 에서 1시간에 30번 틀리면(계정이 달라도) 31번째는 429, 다른 IP 는 된다")
	void loginIpLimit() {
		String email = auth.signedUp();
		auth.ip(PhoneClient.uniqueIp());
		for (int i = 0; i < 30; i++) {
			assertThat(auth.sessionLogin(AuthClient.uniqueEmail(), WRONG).statusCode()).as("failure %d", i + 1)
				.isEqualTo(401);
		}

		assertRateLimited(auth.sessionLogin(email, AuthClient.PASSWORD), 3500, 3600);
		assertThat(auth.ip(PhoneClient.uniqueIp()).sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#1 같은 IP 에서 1시간에 가입 10번까지: 11번째는 429, 다른 IP 는 되고 1시간 뒤에는 다시 된다")
	void signupIpLimit() {
		String ip = PhoneClient.uniqueIp();
		auth.ip(ip);
		for (int i = 0; i < 10; i++) {
			assertThat(auth.signup(AuthClient.uniqueEmail(), AuthClient.PASSWORD, "tester").statusCode())
				.as("signup %d", i + 1)
				.isEqualTo(201);
		}

		String email = AuthClient.uniqueEmail();
		assertRateLimited(auth.signup(email, AuthClient.PASSWORD, "tester"), 3500, 3600);
		assertThat(jdbc.queryForObject("select count(*) from user_identities where subject = ?", Long.class, email))
			.isZero();
		assertThat(auth.ip(PhoneClient.uniqueIp()).signup(email, AuthClient.PASSWORD, "tester").statusCode())
			.isEqualTo(201);
		clock.advance(Duration.ofHours(1));
		assertThat(auth.ip(ip).signup(AuthClient.uniqueEmail(), AuthClient.PASSWORD, "tester").statusCode())
			.isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-22#1 rate_limit_events 에는 이메일·IP 원문을 남기지 않는다(해시만)")
	void storesOnlyKeyHashes() {
		String email = auth.signedUp();
		failLogins(email, 1);

		Long raw = jdbc.queryForObject(
				"select count(*) from rate_limit_events where key_hash like ? or key_hash like ? or key_hash like ?",
				Long.class, "%" + email + "%", "%" + auth.ip() + "%", "%@%");
		assertThat(raw).isZero();
		assertThat(jdbc.queryForList("select key_hash from rate_limit_events", String.class))
			.allSatisfy(hash -> assertThat(hash).matches("^[0-9a-f]{64}$"));
	}

	private User localUser(String email) {
		long id = jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
		return users.findById(id).orElseThrow();
	}

	private HttpResponse<String> stepUp(String bearer, String password) {
		return api.post("/auth/step-up/local").bearer(bearer).json("{\"password\":" + AuthClient.quote(password) + "}")
			.send();
	}

	@Test
	@DisplayName("TASK-22#B1 제한 세션에서 step-up 비밀번호를 5번 틀리면 6번째는 맞아도 429(약 15분), 15분 뒤에는 된다")
	void stepUpGuessingIsLimited() {
		User user = localUser(auth.signedUp());
		String restricted = fixtures.stepUpBearer(user);
		for (int i = 0; i < 5; i++) {
			assertThat(stepUp(restricted, WRONG).statusCode()).as("guess %d", i + 1).isEqualTo(401);
		}

		assertRateLimited(stepUp(restricted, AuthClient.PASSWORD), 880, 900);
		// 다른 제한 토큰(같은 계정)으로 바꿔도 사용자 단위라 막힌다
		assertThat(stepUp(fixtures.stepUpBearer(user), AuthClient.PASSWORD).statusCode()).isEqualTo(429);

		clock.advance(Duration.ofMinutes(15));
		assertThat(stepUp(restricted, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#B1 step-up 성공은 실패로 세지 않는다")
	void successfulStepUpsDoNotCount() {
		User user = localUser(auth.signedUp());
		for (int i = 0; i < 4; i++) {
			assertThat(stepUp(fixtures.stepUpBearer(user), WRONG).statusCode()).isEqualTo(401);
		}
		for (int i = 0; i < 3; i++) {
			assertThat(stepUp(fixtures.stepUpBearer(user), AuthClient.PASSWORD).statusCode()).as("step-up %d", i + 1)
				.isEqualTo(200);
		}

		assertThat(stepUp(fixtures.stepUpBearer(user), WRONG).statusCode()).isEqualTo(401);
		assertThat(stepUp(fixtures.stepUpBearer(user), AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

}
