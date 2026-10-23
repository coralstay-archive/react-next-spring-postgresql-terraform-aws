package com.example.board.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.board.auth.phone.OtpProperties;
import com.example.board.common.RateLimitProperties;
import com.example.board.common.RateLimitProperties.Policy;
import com.example.board.post.PostViewService;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

/**
 * TASK-27#1 BE-10 · TASK-27#5 보존 기간이 지난 행 정리. 기본 설정: 만료·폐기 뒤 7일 지난 refresh_tokens, 만료 뒤 1일이 지났고 OTP 발송
 * 한도 창(24시간) 밖인 otp_challenges, 조회수 중복 집계 창(30분) 밖의 post_views, 어느 글도 쓰지 않는 tags, 가장 긴 레이트리밋 창 밖의
 * rate_limit_events.
 *
 * <p>TASK-27#B1 보안상 필요한 기록(살아 있는 refresh 토큰, 아직 창 안의 OTP·레이트리밋 기록)은 지우지 않는다. 설정에 따라 기준이 바뀌는 경우는
 * 같은 DB 에 다른 설정의 정리 인스턴스를 만들어 확인한다.
 *
 * <p>통합 테스트 컨텍스트와 DB 를 같이 쓰므로 개수가 아니라 이 테스트가 만든 행의 존재로 판정한다.
 */
@IntegrationTest
class RetentionCleanupTest {

	@Autowired
	RetentionCleanup cleanup;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	OtpProperties otp;

	@Autowired
	RateLimitProperties rateLimits;

	@Autowired
	RetentionProperties retention;

	@Autowired
	Clock clock;

	long userId;

	@BeforeEach
	void setUp() {
		userId = fixtures.user().getId();
	}

	private static Timestamp ago(Duration age) {
		return Timestamp.from(Instant.now().minus(age));
	}

	private static Timestamp later(Duration age) {
		return Timestamp.from(Instant.now().plus(age));
	}

	private boolean exists(String table, String idColumn, Object id) {
		return jdbc.queryForObject("select count(*) from " + table + " where " + idColumn + " = ?", Integer.class,
				id) == 1;
	}

	private long refreshToken(Timestamp createdAt, Timestamp expiresAt, Timestamp revokedAt, Long replacedBy) {
		return jdbc.queryForObject("""
				insert into refresh_tokens (user_id, token_hash, family_id, expires_at, revoked_at, replaced_by, created_at)
				values (?, ?, ?, ?, ?, ?, ?) returning id
				""", Long.class, userId, UUID.randomUUID().toString(), UUID.randomUUID(), expiresAt, revokedAt,
				replacedBy, createdAt);
	}

	private UUID otpChallenge(String phone, Timestamp createdAt, Timestamp expiresAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				insert into otp_challenges (id, phone, code_hash, expires_at, created_at, requester_ip)
				values (?, ?, 'hash', ?, ?, '203.0.113.9')
				""", id, phone, expiresAt, createdAt);
		return id;
	}

	private long rateLimitEvent(Timestamp occurredAt) {
		return jdbc.queryForObject(
				"insert into rate_limit_events (bucket, key_hash, occurred_at) values ('test', ?, ?) returning id",
				Long.class, "k".repeat(64), occurredAt);
	}

	private static String phone() {
		return "+8210" + String.format("%08d", (long) (Math.random() * 100_000_000));
	}

	private RetentionCleanup with(RetentionProperties properties, RateLimitProperties limits, OtpProperties otpProperties) {
		return new RetentionCleanup(jdbc, transactionManager, properties, limits, otpProperties, clock);
	}

	@Test
	@DisplayName("TASK-27#1 TASK-27#B1 refresh_tokens: 만료·폐기 뒤 7일이 지난 행만 지운다. 살아 있는 토큰은 아무리 오래돼도 남는다")
	void refreshTokens() {
		long active = refreshToken(ago(Duration.ofDays(400)), later(Duration.ofDays(1)), null, null);
		long expiredLong = refreshToken(ago(Duration.ofDays(30)), ago(Duration.ofDays(8)), null, null);
		long expiredRecent = refreshToken(ago(Duration.ofDays(20)), ago(Duration.ofDays(6)), null, null);
		long revokedLong = refreshToken(ago(Duration.ofDays(9)), later(Duration.ofDays(5)), ago(Duration.ofDays(8)), null);
		long revokedRecent = refreshToken(ago(Duration.ofDays(9)), later(Duration.ofDays(5)), ago(Duration.ofDays(6)),
				null);

		cleanup.cleanUp();

		assertThat(exists("refresh_tokens", "id", active)).as("살아 있는 토큰(B1)").isTrue();
		assertThat(exists("refresh_tokens", "id", expiredLong)).isFalse();
		assertThat(exists("refresh_tokens", "id", expiredRecent)).as("만료 7일 전").isTrue();
		assertThat(exists("refresh_tokens", "id", revokedLong)).isFalse();
		assertThat(exists("refresh_tokens", "id", revokedRecent)).as("폐기 7일 전").isTrue();
	}

	@Test
	@DisplayName("TASK-27#1 TASK-27#B1 refresh_tokens 로테이션 사슬(replaced_by FK): 오래 전에 폐기된 사슬은 같이 지우고, 이전 토큰만 오래됐으면 살아 있는 다음 토큰은 남긴다")
	void refreshTokenChains() {
		// 로테이션은 새 토큰을 나중에 넣으므로 이전 토큰(replaced_by 로 다음을 가리킨다)의 id 가 항상 작다
		long old = refreshToken(ago(Duration.ofDays(30)), ago(Duration.ofDays(16)), ago(Duration.ofDays(20)), null);
		long alive = refreshToken(ago(Duration.ofDays(20)), later(Duration.ofDays(1)), null, null);
		jdbc.update("update refresh_tokens set replaced_by = ? where id = ?", alive, old);
		long oldDead = refreshToken(ago(Duration.ofDays(30)), ago(Duration.ofDays(16)), ago(Duration.ofDays(20)), null);
		long newDead = refreshToken(ago(Duration.ofDays(20)), ago(Duration.ofDays(9)), ago(Duration.ofDays(9)), null);
		jdbc.update("update refresh_tokens set replaced_by = ? where id = ?", newDead, oldDead);

		cleanup.cleanUp();

		assertThat(exists("refresh_tokens", "id", old)).isFalse();
		assertThat(exists("refresh_tokens", "id", alive)).as("살아 있는 토큰(B1)").isTrue();
		assertThat(exists("refresh_tokens", "id", oldDead)).isFalse();
		assertThat(exists("refresh_tokens", "id", newDead)).isFalse();
	}

	@Test
	@DisplayName("TASK-27#1 TASK-27#B1 otp_challenges: 만료 뒤 1일이 지났고 발송 한도 창(24시간) 밖인 행만 지운다")
	void otpChallenges() {
		String phone = phone();
		UUID old = otpChallenge(phone, ago(Duration.ofHours(30)), ago(Duration.ofHours(30).minusMinutes(3)));
		UUID inWindow = otpChallenge(phone, ago(Duration.ofHours(23)), ago(Duration.ofHours(23).minusMinutes(3)));

		cleanup.cleanUp();

		assertThat(exists("otp_challenges", "id", old)).isFalse();
		assertThat(exists("otp_challenges", "id", inWindow)).isTrue();
	}

	@Test
	@DisplayName("TASK-27#B1 otp_challenges 보존 기간을 짧게 잡아도 발송 한도 창(24시간·phone-cooldown 중 긴 것) 안의 행은 남는다 — 지워서 한도가 풀리지 않는다")
	void otpWindowWinsOverRetention() {
		String phone = phone();
		UUID twoHours = otpChallenge(phone, ago(Duration.ofHours(2)), ago(Duration.ofHours(2).minusMinutes(3)));
		UUID twoDays = otpChallenge(phone, ago(Duration.ofDays(2)), ago(Duration.ofDays(2).minusMinutes(3)));
		RetentionProperties shortRetention = new RetentionProperties(retention.interval(),
				retention.refreshTokenRetention(), Duration.ofMinutes(10), retention.batchSize(), retention.maxItems(),
				retention.maxDuration());

		OtpProperties longCooldown = new OtpProperties(otp.ttl(), otp.maxAttempts(), otp.hashKey(), Duration.ofDays(3),
				otp.phoneDailyLimit(), otp.ipHourlyLimit());
		with(shortRetention, rateLimits, longCooldown).cleanUp();
		assertThat(exists("otp_challenges", "id", twoDays)).as("phone-cooldown 3일 창 안").isTrue();
		assertThat(exists("otp_challenges", "id", twoHours)).isTrue();

		with(shortRetention, rateLimits, otp).cleanUp();
		assertThat(exists("otp_challenges", "id", twoHours)).as("24시간 창 안").isTrue();
		assertThat(exists("otp_challenges", "id", twoDays)).isFalse();
		assertThat(exists("otp_challenges", "id", twoHours)).isTrue();
	}

	@Test
	@DisplayName("TASK-27#1 post_views: 중복 집계 창(30분) 밖의 행만 지운다 — 다시 보면 어차피 새로 세므로 결과가 같다")
	void postViews() {
		long post = board.post(userId, null, "조회 정리");
		Duration window = PostViewService.WINDOW;
		jdbc.update("insert into post_views (post_id, viewer_key, viewed_at) values (?, 'u:old', ?), (?, 'u:new', ?)",
				post, ago(window.plusMinutes(1)), post, ago(window.minusMinutes(1)));

		cleanup.cleanUp();

		assertThat(jdbc.queryForList("select viewer_key from post_views where post_id = ?", String.class, post))
			.containsExactly("u:new");
	}

	@Test
	@DisplayName("TASK-27#1 tags: 어느 글도 쓰지 않는 태그는 다음 실행에 지운다. 글(삭제·숨김 포함)이 쓰는 태그는 남는다")
	void unusedTags() {
		String unused = BoardFixtures.unique("unused");
		String used = BoardFixtures.unique("used");
		String deletedPostTag = BoardFixtures.unique("deleted");
		jdbc.update("insert into tags (name) values (?)", unused);
		board.post(userId, null, "태그", used);
		long deleted = board.post(userId, null, "삭제 글", deletedPostTag);
		board.delete(deleted);

		cleanup.cleanUp();

		assertThat(exists("tags", "name", unused)).isFalse();
		assertThat(exists("tags", "name", used)).isTrue();
		assertThat(exists("tags", "name", deletedPostTag)).as("soft delete 글은 복구될 수 있다").isTrue();
	}

	@Test
	@DisplayName("TASK-27#5 TASK-27#B1 rate_limit_events: 설정된 가장 긴 창(기본 1시간)보다 오래된 행만 지운다")
	void rateLimitEvents() {
		assertThat(rateLimits.longestWindow()).isEqualTo(Duration.ofHours(1));
		long old = rateLimitEvent(ago(Duration.ofMinutes(61)));
		long inWindow = rateLimitEvent(ago(Duration.ofMinutes(59)));

		cleanup.cleanUp();

		assertThat(exists("rate_limit_events", "id", old)).isFalse();
		assertThat(exists("rate_limit_events", "id", inWindow)).as("가장 긴 창 안(B1)").isTrue();
	}

	@Test
	@DisplayName("TASK-27#5 TASK-27#B1 rate_limit_events 기준은 실행 때 설정에서 계산한다 — 창 하나를 3시간으로 늘리면 2시간 전 행도 남는다")
	void rateLimitWindowFromConfig() {
		long twoHours = rateLimitEvent(ago(Duration.ofHours(2)));
		RateLimitProperties longer = new RateLimitProperties(null, null, null, null, null, null,
				new Policy(10, Duration.ofHours(3)), null, null);
		assertThat(longer.longestWindow()).isEqualTo(Duration.ofHours(3));

		with(retention, longer, otp).cleanUp();
		assertThat(exists("rate_limit_events", "id", twoHours)).isTrue();

		cleanup.cleanUp();
		assertThat(exists("rate_limit_events", "id", twoHours)).isFalse();
	}

	@Test
	@DisplayName("TASK-27#1 실행당 예산: 표마다 최대 max-items 건만 지우고(batch-size 씩 나눠서) 남은 것은 다음 실행이 지운다")
	void budget() {
		cleanup.cleanUp();
		long[] ids = new long[5];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = rateLimitEvent(ago(Duration.ofHours(2)));
		}
		RetentionProperties small = new RetentionProperties(retention.interval(), retention.refreshTokenRetention(),
				retention.otpRetention(), 2, 3, retention.maxDuration());

		RetentionCleanup.Result first = with(small, rateLimits, otp).cleanUp();

		assertThat(first.rateLimitEvents()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from rate_limit_events where id = any(?)", Integer.class,
				(Object) java.util.Arrays.stream(ids).boxed().toArray(Long[]::new)))
			.isEqualTo(2);

		RetentionCleanup.Result second = with(small, rateLimits, otp).cleanUp();
		assertThat(second.rateLimitEvents()).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-27#1 정리 작업은 스케줄 락(ShedLock retention-cleanup)을 잡고 돈다")
	void scheduledWithLock() {
		cleanup.run();
		assertThat(jdbc.queryForList("select name from shedlock", String.class)).contains(RetentionCleanup.LOCK);
	}

}
