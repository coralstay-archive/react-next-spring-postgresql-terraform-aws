package com.example.board.auth.phone;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.board.common.ApiException;
import com.example.board.sms.PhoneNumbers;
import com.example.board.sms.SmsDeliveryException;
import com.example.board.sms.SmsSender;

/**
 * 휴대폰 OTP 발급·검증 (TASK-7, decision-7).
 *
 * <ul>
 * <li>발급: 번호를 E.164 로 정규화하고, 6자리 코드를 만들어 해시만 저장한 뒤({@link OtpCodes}) SMS 로 보낸다. 계정 존재 여부와
 * 상관없이 같은 응답이다. 저장(커밋)한 뒤에 보낸다 — 발송이 실패해도 그 챌린지는 발송 한도에 포함된다(남용 방지).</li>
 * <li>검증: 챌린지 행을 잠그고(FOR UPDATE) 만료·소멸·시도 초과를 확인한 뒤 코드를 비교한다. 틀리면 attempts 를 올리고, 맞으면
 * 소멸시킨다. 실패 결과도 커밋해야 하므로 예외 대신 빈 값을 돌려준다(시도 횟수 증가가 롤백되지 않게).</li>
 * <li>소모 되돌리기({@link #release}, TASK-25#5 BE-22): 소모는 검증 트랜잭션에서 먼저 커밋되므로, 그 뒤 계정 처리가 실패하면 호출자가
 * 되돌린다. 그래야 사용자가 같은 인증번호로 다시 확인할 수 있다(새 발송·쿨다운·일일 한도를 쓰지 않는다).</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(OtpProperties.class)
public class PhoneOtpService {

	public static final String SMS_UNAVAILABLE = "sms_unavailable";

	private static final Log log = LogFactory.getLog(PhoneOtpService.class);

	private final OtpChallengeRepository challenges;

	private final PhoneNumbers phones;

	private final SmsSender sms;

	private final OtpProperties properties;

	private final OtpCodes codes;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final OtpRateLimiter rateLimiter;

	public PhoneOtpService(OtpChallengeRepository challenges, PhoneNumbers phones, SmsSender sms,
			OtpProperties properties, Clock clock, PlatformTransactionManager transactionManager,
			OtpRateLimiter rateLimiter) {
		this.challenges = challenges;
		this.rateLimiter = rateLimiter;
		this.phones = phones;
		this.sms = sms;
		this.properties = properties;
		this.codes = new OtpCodes(properties.hashKey());
		this.clock = clock;
		this.tx = new TransactionTemplate(transactionManager);
		if (properties.hashKey().startsWith("dev-only-")) {
			log.warn("app.otp.hash-key 가 개발용 기본값이다. 운영에서는 env OTP_HASH_KEY 로 반드시 바꾼다.");
		}
	}

	/**
	 * 번호를 정규화하고 챌린지를 만들어 SMS 를 보낸다. 잘못된 번호는 400 invalid_phone, 허용 국가의 휴대폰이 아니면 400
	 * phone_not_allowed(TASK-22#4 — 발송 한도보다 먼저 검사해 한도를 소모하지 않는다), 발송 한도 초과는 429 rate_limited
	 * ({@link OtpRateLimiter}), 발송 실패는 503.
	 */
	public OtpChallengeResponse send(String rawPhone, String clientIp) {
		String phone = phones.sendable(rawPhone);
		String code = codes.generate();
		UUID id = UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			Instant now = clock.instant();
			rateLimiter.check(phone, clientIp, now);
			challenges.saveAndFlush(OtpChallenge.issue(id, phone, codes.hash(id, phone, code), now,
					now.plus(properties.ttl()), clientIp));
		});
		try {
			sms.send(phone, message(code));
		}
		catch (SmsDeliveryException ex) {
			log.warn("OTP SMS 발송 실패: to=" + PhoneNumbers.mask(phone) + " challenge=" + id, ex);
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, SMS_UNAVAILABLE,
					"인증번호를 보내지 못했습니다. 잠시 후 다시 시도하세요");
		}
		return new OtpChallengeResponse(id, properties.ttl().toSeconds());
	}

	/**
	 * 코드를 검증한다. 성공하면 챌린지를 소멸시키고 번호(E.164)를 돌려준다. 없는·만료된·이미 쓴·시도 초과 챌린지, 틀린 코드는 모두 빈 값
	 * (호출자는 구분 없이 401 invalid_otp). 틀린 코드는 attempts 를 1 올린다.
	 */
	public Optional<String> verify(UUID challengeId, String code) {
		return tx.execute(status -> {
			Instant now = clock.instant();
			OtpChallenge challenge = challenges.findForUpdateById(challengeId).orElse(null);
			if (challenge == null || !challenge.isUsable(now, properties.maxAttempts())) {
				return Optional.<String>empty();
			}
			if (!codes.matches(challenge.getCodeHash(), challengeId, challenge.getPhone(), code)) {
				challenge.failedAttempt();
				return Optional.<String>empty();
			}
			challenge.consume(now);
			return Optional.of(challenge.getPhone());
		});
	}

	/**
	 * 검증에 성공해 소모된 챌린지를 다시 쓸 수 있게 되돌린다(새 트랜잭션). 만료 시각·시도 횟수는 그대로다. 되돌리기 자체가 실패하면(DB 장애)
	 * WARN(챌린지 id·예외 종류만) 후 {@code cause} 에 suppressed 로 붙이고 던지지 않는다 — 호출자는 원래 예외를 그대로 낸다.
	 */
	public void release(UUID challengeId, RuntimeException cause) {
		try {
			tx.executeWithoutResult(status -> challenges.release(challengeId));
			log.info("OTP 확인 뒤 계정 처리 실패 — 챌린지 소모를 되돌림: challenge=" + challengeId + " 오류="
					+ cause.getClass().getSimpleName());
		}
		catch (RuntimeException ex) {
			log.warn("OTP 챌린지 소모 되돌리기 실패(그 인증번호는 다시 쓸 수 없다): challenge=" + challengeId + " 오류="
					+ ex.getClass().getSimpleName());
			cause.addSuppressed(ex);
		}
	}

	public OtpProperties properties() {
		return properties;
	}

	private String message(String code) {
		return "[board] 인증번호 " + code + " (" + properties.ttl().toMinutes() + "분 안에 입력하세요)";
	}

}
