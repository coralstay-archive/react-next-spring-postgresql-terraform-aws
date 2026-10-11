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
	 * 번호를 정규화하고 챌린지를 만들어 SMS 를 보낸다. 잘못된 번호는 400 invalid_phone, 발송 한도 초과는 429 rate_limited
	 * ({@link OtpRateLimiter}), 발송 실패는 503.
	 */
	public OtpChallengeResponse send(String rawPhone, String clientIp) {
		String phone = phones.normalize(rawPhone);
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

	public OtpProperties properties() {
		return properties;
	}

	private String message(String code) {
		return "[board] 인증번호 " + code + " (" + properties.ttl().toMinutes() + "분 안에 입력하세요)";
	}

}
