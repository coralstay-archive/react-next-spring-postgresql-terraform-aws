package com.example.board.auth.phone;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 휴대폰 OTP 설정 ({@code app.otp.*}).
 *
 * @param ttl 인증번호 유효 시간 (기본 3분, B1)
 * @param maxAttempts 챌린지 하나에 허용하는 틀린 검증 횟수 (기본 5). 이에 이르면 챌린지 폐기 (B2)
 * @param hashKey 코드 해시(HMAC-SHA256) 키. 32바이트 이상. 바꾸면 진행 중인 챌린지만 무효가 된다
 * @param phoneCooldown 같은 번호로 다시 보내기까지 기다릴 시간 (기본 60초, B3)
 * @param phoneDailyLimit 같은 번호로 24시간 동안 보낼 수 있는 횟수 (기본 10, B3)
 * @param ipHourlyLimit 같은 클라이언트 IP 에서 1시간 동안 보낼 수 있는 횟수 (기본 20)
 */
@ConfigurationProperties("app.otp")
public record OtpProperties(Duration ttl, Integer maxAttempts, String hashKey, Duration phoneCooldown,
		Integer phoneDailyLimit, Integer ipHourlyLimit) {

	public static final int MIN_KEY_BYTES = 32;

	public OtpProperties {
		if (hashKey == null || hashKey.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_BYTES) {
			throw new IllegalArgumentException(
					"app.otp.hash-key 는 " + MIN_KEY_BYTES + "바이트 이상이어야 한다 (env OTP_HASH_KEY)");
		}
		ttl = ttl == null ? Duration.ofMinutes(3) : ttl;
		maxAttempts = maxAttempts == null ? 5 : maxAttempts;
		phoneCooldown = phoneCooldown == null ? Duration.ofSeconds(60) : phoneCooldown;
		phoneDailyLimit = phoneDailyLimit == null ? 10 : phoneDailyLimit;
		ipHourlyLimit = ipHourlyLimit == null ? 20 : ipHourlyLimit;
	}

}
