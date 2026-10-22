package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * TASK-25#6 BE-23 OTP 한도 설정({@code app.otp.*})은 기동 시 검증한다: 횟수(max-attempts·phone-daily-limit·ip-hourly-limit)는 1 이상,
 * ttl 은 양수, phone-cooldown 은 음수 금지. 어기면 기동하지 않는다(요청 때 NPE·500 이 아니라).
 */
class OtpPropertiesTest {

	static final String KEY = "app.otp.hash-key=test-only-otp-hash-key-0123456789abcdef";

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OtpProperties.class)
	static class Config {

	}

	private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class)
		.withPropertyValues(KEY);

	@Test
	@DisplayName("TASK-25#6 기본값과 1 이상의 값은 그대로 뜬다")
	void valid() {
		context.run(ctx -> {
			OtpProperties properties = ctx.getBean(OtpProperties.class);
			assertThat(properties.maxAttempts()).isEqualTo(5);
			assertThat(properties.phoneDailyLimit()).isEqualTo(10);
			assertThat(properties.ipHourlyLimit()).isEqualTo(20);
		});
		context
			.withPropertyValues("app.otp.max-attempts=1", "app.otp.phone-daily-limit=1", "app.otp.ip-hourly-limit=1",
					"app.otp.phone-cooldown=0s", "app.otp.ttl=1s")
			.run(ctx -> assertThat(ctx.getBean(OtpProperties.class).phoneCooldown()).isEqualTo(Duration.ZERO));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "app.otp.max-attempts=0", "app.otp.phone-daily-limit=0", "app.otp.ip-hourly-limit=0",
			"app.otp.max-attempts=-1", "app.otp.phone-daily-limit=-5", "app.otp.ip-hourly-limit=-1", "app.otp.ttl=0s",
			"app.otp.ttl=-1s", "app.otp.phone-cooldown=-1s" })
	@DisplayName("TASK-25#6 0·음수 한도는 기동 실패 — 원인에 설정 키 이름")
	void invalid(String property) {
		String key = property.substring(0, property.indexOf('='));
		context.withPropertyValues(property).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining(key);
		});
	}

}
