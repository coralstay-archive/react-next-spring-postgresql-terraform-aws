package com.example.board.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공용 레이트리밋 (TASK-22). {@code OtpRateLimiter}(otp_challenges 기반) 패턴을 일반화했다: 한도마다(bucket) 키별 이벤트 시각을
 * {@code rate_limit_events} 에 쌓고, 슬라이딩 창(지금부터 거꾸로 {@code window}) 안의 개수가 {@code max} 이상이면 429
 * {@link RateLimitedException} + {@code Retry-After}(창 안 이벤트가 하나 빠져 한도 아래로 내려가는 데 남은 초)를 던진다.
 *
 * <ul>
 * <li>DB 하나를 보므로 인스턴스가 여러 개여도 같은 한도다.</li>
 * <li>"세고 → 넣기" 사이의 경쟁은 (bucket, 키) 별 트랜잭션 advisory lock 으로 직렬화한다. 여러 규칙은 잠금 키 순서로 잠가 교착이
 * 없다. 지금 시각은 잠근 뒤에 읽는다(먼저 커밋한 요청의 이벤트를 반드시 본다).</li>
 * <li>키는 SHA-256(bucket + 키) hex 로만 저장한다 — 이메일·IP·사용자 id 원문을 남기지 않는다.</li>
 * <li>지금보다 뒤 시각의 이벤트는 세지 않는다(시계를 되돌린 테스트끼리 섞이지 않게).</li>
 * </ul>
 *
 * 두 가지 쓰임:
 * <ul>
 * <li>{@link #hit}: 시도 자체를 센다(가입·글·댓글·presign). 현재 트랜잭션이 있으면 거기에 참여한다 — 롤백되면(예: 검증 실패)
 * 기록도 사라져 한도를 소모하지 않고, 잠금은 커밋까지 유지된다.</li>
 * <li>{@link #reserve}/{@link #release}: 실패만 센다(로그인·step-up). 검사 전에 이벤트를 미리 넣고 바로 커밋해(동시 요청도 그
 * 자리를 본다 → 비밀번호 검사 중인 요청까지 세어 한도를 넘지 않는다) 성공하면 지운다.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

	private final JdbcTemplate jdbc;

	private final Clock clock;

	private final TransactionTemplate required;

	private final TransactionTemplate requiresNew;

	public RateLimiter(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.required = new TransactionTemplate(transactionManager);
		this.requiresNew = new TransactionTemplate(transactionManager);
		this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** 한 키에 대한 한도. bucket 은 코드 상수, key 는 정규화한 원문(저장 전에 해시한다). */
	public record Rule(String bucket, String key, RateLimitProperties.Policy policy) {

		String keyHash() {
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest((bucket + "\n" + key).getBytes(StandardCharsets.UTF_8));
				return HexFormat.of().formatHex(digest);
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

	/** {@link #reserve} 가 넣은 이벤트. 성공하면 {@link #release} 로 지운다. */
	public record Reservation(List<Long> ids) {
	}

	/** 모든 규칙이 한도 아래면 이벤트를 하나씩 기록한다. 하나라도 넘으면 429(아무것도 기록하지 않는다). */
	public void hit(Rule... rules) {
		required.executeWithoutResult(status -> acquire(rules));
	}

	/** {@link #hit} 과 같지만 별도 트랜잭션으로 바로 커밋하고, 넣은 이벤트를 돌려준다. */
	public Reservation reserve(Rule... rules) {
		return new Reservation(requiresNew.execute(status -> acquire(rules)));
	}

	/** 예약한 이벤트를 지운다(성공한 시도는 세지 않는다). */
	public void release(Reservation reservation) {
		if (reservation.ids().isEmpty()) {
			return;
		}
		requiresNew.executeWithoutResult(status -> jdbc.update(
				"delete from rate_limit_events where id = any(?)", (Object) reservation.ids().toArray(Long[]::new)));
	}

	private List<Long> acquire(Rule[] rules) {
		record Keyed(Rule rule, String hash) {
		}
		List<Keyed> keyed = Arrays.stream(rules)
			.map(rule -> new Keyed(rule, rule.keyHash()))
			.sorted(Comparator.comparing((Keyed k) -> k.rule().bucket()).thenComparing(Keyed::hash))
			.toList();
		keyed.forEach(k -> jdbc.queryForList("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class,
				k.rule().bucket() + ":" + k.hash()));
		Instant now = clock.instant();
		Instant until = null;
		for (Keyed k : keyed) {
			Instant blocker = nthNewest(k.rule(), k.hash(), now);
			if (blocker != null) {
				Instant freed = blocker.plus(k.rule().policy().window());
				until = until == null || freed.isAfter(until) ? freed : until;
			}
		}
		if (until != null) {
			throw limited(until, now);
		}
		List<Long> ids = new ArrayList<>();
		OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
		for (Keyed k : keyed) {
			ids.add(jdbc.queryForObject(
					"insert into rate_limit_events (bucket, key_hash, occurred_at) values (?, ?, ?) returning id",
					Long.class, k.rule().bucket(), k.hash(), at));
		}
		return ids;
	}

	/**
	 * 창 안에서 {@code max} 번째로 최근 이벤트의 시각. 있으면 한도에 이른 것이고, 이 이벤트가 창 밖으로 나갈 때 한도 아래로 내려간다.
	 */
	private Instant nthNewest(Rule rule, String hash, Instant now) {
		List<OffsetDateTime> rows = jdbc.queryForList(
				"select occurred_at from rate_limit_events where bucket = ? and key_hash = ? and occurred_at > ?"
						+ " and occurred_at <= ? order by occurred_at desc offset ? limit 1",
				OffsetDateTime.class, rule.bucket(), hash, now.minus(rule.policy().window()).atOffset(ZoneOffset.UTC),
				now.atOffset(ZoneOffset.UTC), rule.policy().max() - 1);
		return rows.isEmpty() ? null : rows.get(0).toInstant();
	}

	private static RateLimitedException limited(Instant until, Instant now) {
		long millis = Duration.between(now, until).toMillis();
		return new RateLimitedException((millis + 999) / 1000, "요청이 너무 많습니다. 잠시 후 다시 시도하세요");
	}

}
