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
 * {@code rate_limited} + {@code Retry-After}(초). 기본값: 로그인 (계정, IP)별 15분 창 안 실패 5회·IP별 1시간 30회 실패, 가입 IP별
 * 1시간 10회, step-up 사용자별 15분 창 안 실패 5회. 고정 15분 잠금이 아니라 슬라이딩 창이다 — 가장 오래된 실패가 창 밖으로 나가면
 * 한 번 더 시도할 수 있다. 시계는 테스트 시계로 움직이고, 키(이메일·IP·사용자)는 테스트마다 새로 만든다.
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

	/** 지금 IP({@code auth.ip()})에서 틀린 비밀번호로 로그인한다. */
	private void failLogins(String email, int times) {
		for (int i = 0; i < times; i++) {
			HttpResponse<String> response = auth.sessionLogin(email, WRONG);
			assertThat(response.statusCode()).as("failure %d: %s", i + 1, response.body()).isEqualTo(401);
		}
	}

	@Test
	@DisplayName("TASK-22#1 같은 (계정, IP)로 15분 창 안에 5번 틀리면 6번째는 맞는 비밀번호여도 429 + Retry-After(약 15분)")
	void loginAccountLockout() {
		String email = auth.signedUp();
		failLogins(email, 5);

		HttpResponse<String> blocked = auth.sessionLogin(email, AuthClient.PASSWORD);

		assertRateLimited(blocked, 880, 900);
		assertThat(Api.cookie(blocked, "SESSION")).isEmpty();
		assertThat(auth.jwtLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#1 계정 한도는 (계정, IP) 조합이다: IP A 에서 5번 틀려 A 가 막혀도 IP B 에서는 맞는 비밀번호로 로그인된다(잠금 DoS 방지)")
	void loginAccountLimitIsPerAccountAndIp() {
		String email = auth.signedUp();
		String ipA = auth.ip();
		failLogins(email, 5);
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);

		String ipB = PhoneClient.uniqueIp();
		assertThat(ipB).isNotEqualTo(ipA);
		assertThat(auth.ip(ipB).sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
		// B 에서도 실패는 B 몫으로 따로 센다
		failLogins(email, 5);
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
		assertThat(auth.ip(ipA).sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	@DisplayName("TASK-22#1 한꺼번에 낸 5번 실패는 15분 창이 지나면 모두 빠져 풀린다(창 경계)")
	void loginAccountLockoutElapses() {
		String email = auth.signedUp();
		failLogins(email, 5);
		clock.advance(Duration.ofMinutes(15).minusSeconds(30));
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(429);

		clock.advance(Duration.ofSeconds(31));

		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#1 슬라이딩 창: t0 에 1번·t0+14:50 에 4번 틀리면 막히고 Retry-After 는 t0 실패가 창 밖으로 나갈 때까지(약 10초)."
			+ " 그 뒤 한 번 더 시도할 수 있고, 다시 다음으로 오래된 실패가 나갈 때까지 막힌다")
	void loginSlidingWindow() {
		String email = auth.signedUp();
		failLogins(email, 1);
		clock.advance(Duration.ofMinutes(14).plusSeconds(50));
		failLogins(email, 4);

		HttpResponse<String> blocked = auth.sessionLogin(email, AuthClient.PASSWORD);
		assertRateLimited(blocked, 1, 10);

		clock.advance(Duration.ofSeconds(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())));
		assertThat(auth.sessionLogin(email, WRONG).statusCode()).as("t0 실패가 창 밖으로 나가 한 번 더").isEqualTo(401);
		// 남은 4번(t0+14:50)과 방금 실패로 다시 5번 — 이번엔 t0+14:50 실패가 나갈 때까지(약 14분 50초)
		assertRateLimited(auth.sessionLogin(email, AuthClient.PASSWORD), 870, 890);
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

		HttpResponse<String> forExisting = auth.sessionLogin(existing, WRONG);
		HttpResponse<String> forMissing = auth.sessionLogin(missing, WRONG);

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
	@DisplayName("TASK-22#B1 제한 세션에서 step-up 비밀번호를 15분 창 안에 5번 틀리면 6번째는 맞아도 429(약 15분), 15분 뒤에는 된다")
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
	@DisplayName("TASK-22#B1 step-up 도 15분 창 안 실패 5회(슬라이딩 창): t0 에 1번·t0+14:50 에 4번 틀리면 약 10초 막히고,"
			+ " 그 뒤 한 번 더 시도할 수 있고 다시 막힌다")
	void stepUpSlidingWindow() {
		User user = localUser(auth.signedUp());
		// access 토큰 수명도 15분이라 시도마다 새 제한 토큰을 쓴다(같은 사용자)
		assertThat(stepUp(fixtures.stepUpBearer(user), WRONG).statusCode()).isEqualTo(401);
		clock.advance(Duration.ofMinutes(14).plusSeconds(50));
		for (int i = 0; i < 4; i++) {
			assertThat(stepUp(fixtures.stepUpBearer(user), WRONG).statusCode()).as("guess %d", i + 2).isEqualTo(401);
		}

		HttpResponse<String> blocked = stepUp(fixtures.stepUpBearer(user), AuthClient.PASSWORD);
		assertRateLimited(blocked, 1, 10);

		clock.advance(Duration.ofSeconds(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())));
		assertThat(stepUp(fixtures.stepUpBearer(user), WRONG).statusCode()).as("t0 실패가 창 밖으로 나가 한 번 더").isEqualTo(401);
		assertRateLimited(stepUp(fixtures.stepUpBearer(user), AuthClient.PASSWORD), 870, 890);
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
