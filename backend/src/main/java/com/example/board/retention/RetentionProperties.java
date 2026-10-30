package com.example.board.retention;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 보존 기간 정리 설정 {@code app.retention.*} (TASK-27#1 BE-10, TASK-27#5). 기동 시 검증한다 — 어기면 뜨지 않는다.
 *
 * @param interval 정리 주기 (기본 1시간). 양수
 * @param refreshTokenRetention 끝난 refresh 토큰 family 를 지우기 전 보존 기간 (기본 7일). family 단위로, 살아 있는 토큰이 하나도 없고 마지막
 * 토큰이 폐기·만료된 지 이 기간이 지난 family 만 통째로 지운다. 살아 있는 family 는 로테이션된 토큰까지 남겨 재사용 감지를 유지한다(TASK-27 B1)
 * @param otpRetention OTP 챌린지를 만료 뒤 지우기 전 보존 기간 (기본 1일). 실제 기준은 이 값과 발송 한도 창(24시간·1시간·phone-cooldown) 중
 * 늦은 쪽이다 — 지워서 발송 한도가 풀리지 않는다(B1)
 * @param batchSize DELETE 한 문장이 지우는 최대 행 수 (기본 1000, 1~{@value #MAX_BATCH_SIZE}). 긴 잠금·긴 트랜잭션을 피한다
 * @param maxItems 한 번 실행에서 표마다 지우는 최대 행 수 (기본 50000). 남은 것은 다음 실행이 지운다
 * @param maxDuration 한 번 실행의 최대 시간 (기본 10분). 스케줄 락 lockAtMostFor(30분)보다 충분히 짧게 {@value #MAX_DURATION_MINUTES}분
 * 이하
 */
@ConfigurationProperties("app.retention")
public record RetentionProperties(@DefaultValue("PT1H") Duration interval,
		@DefaultValue("P7D") Duration refreshTokenRetention, @DefaultValue("P1D") Duration otpRetention,
		@DefaultValue("1000") int batchSize, @DefaultValue("50000") int maxItems,
		@DefaultValue("PT10M") Duration maxDuration) {

	public static final int MAX_BATCH_SIZE = 10000;

	public static final int MAX_DURATION_MINUTES = 20;

	public RetentionProperties {
		positive(interval, "interval");
		positive(refreshTokenRetention, "refresh-token-retention");
		positive(otpRetention, "otp-retention");
		positive(maxDuration, "max-duration");
		if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
			throw new IllegalArgumentException(
					"app.retention.batch-size 는 1~" + MAX_BATCH_SIZE + " 이어야 한다: " + batchSize);
		}
		if (maxItems < 1) {
			throw new IllegalArgumentException("app.retention.max-items 는 양수여야 한다: " + maxItems);
		}
		if (maxDuration.compareTo(Duration.ofMinutes(MAX_DURATION_MINUTES)) > 0) {
			throw new IllegalArgumentException("app.retention.max-duration(" + maxDuration + ") 은 "
					+ MAX_DURATION_MINUTES + "분(스케줄 락 lockAtMostFor 30분보다 짧게)을 넘을 수 없다");
		}
	}

	private static void positive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("app.retention." + name + " 는 양수여야 한다: " + value);
		}
	}

}
