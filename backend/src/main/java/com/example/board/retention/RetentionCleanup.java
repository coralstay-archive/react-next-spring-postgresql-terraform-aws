package com.example.board.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.IntUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.board.auth.phone.OtpProperties;
import com.example.board.auth.phone.OtpRateLimiter;
import com.example.board.common.RateLimitProperties;
import com.example.board.post.PostViewService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * 보존 기간이 지난 행 정리 (TASK-27#1 BE-10, TASK-27#5). {@code app.retention.interval}(기본 1시간)마다 표 다섯 개를 차례로 정리한다. 기준은
 * 모두 "이 행이 더 이상 어떤 판단에도 쓰이지 않는다"이다 — 보안상 필요한 기록(살아 있는 refresh 토큰, 아직 창 안의 OTP·레이트리밋 기록)은 지우지
 * 않는다(TASK-27 B1).
 *
 * <ul>
 * <li>refresh_tokens: 폐기(revoked_at) 또는 만료(expires_at) 뒤 {@code refresh-token-retention}(7일)이 지난 행. 살아 있는 토큰(폐기 안
 * 됨·만료 안 됨)은 조건에 걸릴 수 없다. 이전 토큰이 {@code replaced_by} 로 다음 토큰을 가리키는데(FK), 다음 토큰이 지울 대상이면 이전 토큰도
 * 대상이고(로테이션 때 폐기됐으므로 폐기 시각이 더 이르다) id 가 더 작다 — id 순 배치라 이전 토큰이 같은 문장이나 앞 문장에서 먼저 지워진다.</li>
 * <li>otp_challenges: 만료 뒤 {@code otp-retention}(1일)이 지났고 created_at 이 발송 한도의 가장 긴 창
 * ({@link OtpRateLimiter#longestWindow}) 밖인 행. 발송 한도는 이 표의 created_at 을 세므로 창 안의 행을 지우면 한도가 풀린다.</li>
 * <li>post_views: viewed_at 이 중복 집계 창({@link PostViewService#WINDOW}) 밖인 행. 이런 행은 다음 조회 때 어차피 다시 세므로(upsert 의
 * {@code WHERE viewed_at <= now-창}) 지워도 결과가 같다.</li>
 * <li>tags: 어느 글(삭제·숨김 글 포함)도 쓰지 않는 태그. 글 저장과의 경쟁은 {@link #deleteUnusedTags} 참고.</li>
 * <li>rate_limit_events: occurred_at 이 설정된 레이트리밋 창 중 가장 긴 것({@link RateLimitProperties#longestWindow}, 실행 때 계산) 밖인
 * 행.</li>
 * </ul>
 *
 * <p>DELETE 한 문장은 {@code batch-size} 행까지만 지운다(긴 잠금·긴 트랜잭션 없음, autocommit). 한 번 실행은 표마다 {@code max-items} 건,
 * 전체 {@code max-duration} 까지 — 넘으면 남은 것은 다음 실행이 지운다. 로그에는 표 이름과 지운 개수만 남긴다.
 */
@Component
@EnableConfigurationProperties(RetentionProperties.class)
public class RetentionCleanup {

	/** ShedLock 락 이름. */
	static final String LOCK = "retention-cleanup";

	private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);

	static final String DELETE_REFRESH_TOKENS = """
			delete from refresh_tokens where id in (
				select id from refresh_tokens where revoked_at < ? or expires_at < ? order by id limit ?)
			""";

	static final String DELETE_OTP_CHALLENGES = """
			delete from otp_challenges where id in (
				select id from otp_challenges where expires_at < ? and created_at < ? order by created_at limit ?)
			""";

	static final String DELETE_POST_VIEWS = """
			delete from post_views where (post_id, viewer_key) in (
				select post_id, viewer_key from post_views where viewed_at < ? order by viewed_at limit ?)
			and viewed_at < ?
			""";

	static final String LOCK_UNUSED_TAGS = """
			select id from tags t where not exists (select 1 from post_tags pt where pt.tag_id = t.id)
			order by id limit ? for update skip locked
			""";

	static final String DELETE_LOCKED_UNUSED_TAGS = """
			delete from tags t where t.id = any(?) and not exists (select 1 from post_tags pt where pt.tag_id = t.id)
			""";

	static final String DELETE_RATE_LIMIT_EVENTS = """
			delete from rate_limit_events where id in (
				select id from rate_limit_events where occurred_at < ? order by occurred_at limit ?)
			""";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate tx;

	private final RetentionProperties properties;

	private final RateLimitProperties rateLimits;

	private final OtpProperties otp;

	private final Clock clock;

	public RetentionCleanup(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			RetentionProperties properties, RateLimitProperties rateLimits, OtpProperties otp, Clock clock) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.rateLimits = rateLimits;
		this.otp = otp;
		this.clock = clock;
	}

	/** 표마다 지운 행 수. */
	public record Result(int refreshTokens, int otpChallenges, int postViews, int tags, int rateLimitEvents) {

		int total() {
			return refreshTokens + otpChallenges + postViews + tags + rateLimitEvents;
		}

	}

	/** 주기 실행: 인스턴스가 여럿이어도 한 번에 하나만 돈다(ShedLock {@value #LOCK}). 락을 못 잡으면 건너뛴다. */
	@Scheduled(fixedDelayString = "${app.retention.interval:PT1H}", initialDelayString = "${app.retention.interval:PT1H}")
	@SchedulerLock(name = LOCK, lockAtMostFor = "PT30M", lockAtLeastFor = "${app.scheduling.lock-at-least-for:PT10S}")
	public void run() {
		cleanUp();
	}

	/** 락 없이 바로 돈다(테스트·수동 실행용). */
	public Result cleanUp() {
		Instant now = clock.instant();
		Budget budget = new Budget(now.plus(properties.maxDuration()));
		OffsetDateTime refreshCutoff = at(now.minus(properties.refreshTokenRetention()));
		OffsetDateTime otpExpiredCutoff = at(now.minus(properties.otpRetention()));
		OffsetDateTime otpWindowCutoff = at(now.minus(OtpRateLimiter.longestWindow(otp)));
		OffsetDateTime viewCutoff = at(now.minus(PostViewService.WINDOW));
		OffsetDateTime rateLimitCutoff = at(now.minus(rateLimits.longestWindow()));

		int refreshTokens = sweep(budget,
				limit -> jdbc.update(DELETE_REFRESH_TOKENS, refreshCutoff, refreshCutoff, limit));
		int otpChallenges = sweep(budget,
				limit -> jdbc.update(DELETE_OTP_CHALLENGES, otpExpiredCutoff, otpWindowCutoff, limit));
		int postViews = sweep(budget, limit -> jdbc.update(DELETE_POST_VIEWS, viewCutoff, limit, viewCutoff));
		int tags = sweep(budget, this::deleteUnusedTags);
		int rateLimitEvents = sweep(budget,
				limit -> jdbc.update(DELETE_RATE_LIMIT_EVENTS, rateLimitCutoff, limit));

		Result result = new Result(refreshTokens, otpChallenges, postViews, tags, rateLimitEvents);
		if (budget.expired) {
			log.info("보존 기간 정리: 실행당 최대 시간({})을 다 써서 멈춤 — 남은 것은 다음 실행", properties.maxDuration());
		}
		if (result.total() > 0) {
			log.info("보존 기간 정리: refresh_tokens={} otp_challenges={} post_views={} tags={} rate_limit_events={}",
					refreshTokens, otpChallenges, postViews, tags, rateLimitEvents);
		}
		return result;
	}

	/**
	 * 미사용 태그를 최대 {@code limit} 개 잠그고 지운다. 처리한(잠근) 개수를 돌려준다 — 배치를 이어 갈지는 이 개수로 정한다.
	 *
	 * <p>글 저장과의 경쟁: 글 저장({@code TagService.resolve})은 태그를 upsert 한 뒤 태그 행을 {@code FOR KEY SHARE} 로 잠그고 나서야
	 * post_tags 를 넣는다.
	 * <ol>
	 * <li>한 트랜잭션에서 먼저 미사용 태그를 {@code FOR UPDATE SKIP LOCKED} 로 잠근다 — 저장 중인 글이 잡고 있는 태그는 건너뛴다(기다리지
	 * 않는다).</li>
	 * <li>새 문장(새 스냅샷)으로 아직 아무도 안 쓰는지 다시 보고 지운다. 첫 문장의 스냅샷 뒤에 커밋된 post_tags 도 여기서 보인다. 잠근 뒤에는 새
	 * 글이 이 태그를 쓰려면 잠금을 기다려야 하므로, 재확인과 삭제 사이에 끼어들 수 없다. 재확인이 없으면 ON DELETE CASCADE 가 막 커밋된 글의
	 * 태그 연결까지 지울 수 있다.</li>
	 * <li>기다리던 글 저장은 태그가 지워진 것을 보고 다시 만든다({@code TagService.resolve}).</li>
	 * </ol>
	 */
	int deleteUnusedTags(int limit) {
		Integer locked = tx.execute(status -> {
			List<Long> ids = jdbc.queryForList(LOCK_UNUSED_TAGS, Long.class, limit);
			if (!ids.isEmpty()) {
				jdbc.update(DELETE_LOCKED_UNUSED_TAGS, (Object) ids.toArray(Long[]::new));
			}
			return ids.size();
		});
		return locked == null ? 0 : locked;
	}

	/** 한 표를 배치로 지운다. 배치가 덜 찼거나(남은 것 없음) 표의 건수 예산·전체 시간을 다 쓰면 멈춘다. 지운(처리한) 행 수. */
	private int sweep(Budget budget, IntUnaryOperator batch) {
		int done = 0;
		while (true) {
			int limit = Math.min(properties.batchSize(), properties.maxItems() - done);
			if (limit <= 0 || budget.expired()) {
				return done;
			}
			int deleted = batch.applyAsInt(limit);
			done += deleted;
			if (deleted < limit) {
				return done;
			}
		}
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** 한 번 실행의 시간 예산. 다 쓰면 그 뒤 표는 건너뛴다. */
	private final class Budget {

		private final Instant deadline;

		private boolean expired;

		Budget(Instant deadline) {
			this.deadline = deadline;
		}

		boolean expired() {
			if (!expired && !clock.instant().isBefore(deadline)) {
				expired = true;
			}
			return expired;
		}

	}

}
