package com.example.board.auth.phone;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.board.common.RateLimitedException;

/**
 * 발송 레이트리밋 (TASK-7#4, B3). 별도 카운터 없이 otp_challenges 의 created_at 을 센다 → DB 하나를 보므로 인스턴스가 여러 개여도
 * 같은 한도다. 창은 모두 슬라이딩(지금부터 거꾸로).
 *
 * <ul>
 * <li>번호: 직전 발송 후 {@code phoneCooldown}(60초) 안이면 거절, 24시간 동안 {@code phoneDailyLimit}(10)회.</li>
 * <li>IP: 1시간 동안 {@code ipHourlyLimit}(20)회.</li>
 * </ul>
 * "세고 → 넣기" 사이의 경쟁은 트랜잭션 범위 advisory lock(번호, 그다음 IP 순서 — 순서가 같아 교착이 없다)으로 직렬화한다.
 * 반드시 발급 트랜잭션 안에서 부른다(잠금은 커밋 때 풀린다).
 */
@Component
public class OtpRateLimiter {

	private static final Duration DAY = Duration.ofHours(24);

	private static final Duration HOUR = Duration.ofHours(1);

	private final JdbcTemplate jdbc;

	private final OtpProperties properties;

	public OtpRateLimiter(JdbcTemplate jdbc, OtpProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	public void check(String phone, String ip, Instant now) {
		lock("otp:phone:" + phone);
		if (ip != null) {
			lock("otp:ip:" + ip);
		}
		Instant latest = latest("phone", phone);
		if (latest != null && latest.plus(properties.phoneCooldown()).isAfter(now)) {
			throw limited(latest.plus(properties.phoneCooldown()), now);
		}
		Window daily = window("phone", phone, now.minus(DAY));
		if (daily.count() >= properties.phoneDailyLimit()) {
			throw limited(daily.oldest().plus(DAY), now);
		}
		if (ip != null) {
			Window hourly = window("requester_ip", ip, now.minus(HOUR));
			if (hourly.count() >= properties.ipHourlyLimit()) {
				throw limited(hourly.oldest().plus(HOUR), now);
			}
		}
	}

	private void lock(String key) {
		jdbc.queryForList("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class, key);
	}

	private Instant latest(String column, String value) {
		return toInstant(jdbc.queryForObject("select max(created_at) from otp_challenges where " + column + " = ?",
				OffsetDateTime.class, value));
	}

	/** column 은 이 클래스 안의 상수만 넘긴다(사용자 입력 아님). */
	private Window window(String column, String value, Instant since) {
		return jdbc.queryForObject(
				"select count(*), min(created_at) from otp_challenges where " + column + " = ? and created_at > ?",
				(rs, row) -> new Window(rs.getLong(1), toInstant(rs.getObject(2, OffsetDateTime.class))), value,
				since.atOffset(ZoneOffset.UTC));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static RateLimitedException limited(Instant until, Instant now) {
		long millis = Duration.between(now, until).toMillis();
		long seconds = (millis + 999) / 1000;
		return new RateLimitedException(seconds, "요청이 너무 많습니다. 잠시 후 다시 시도하세요");
	}

	private record Window(long count, Instant oldest) {
	}

}
