package com.example.board.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * TASK-27#1 정리 작업 설정({@code app.retention.*})은 기동 시 검증한다: 기간·건수는 양수, 실행당 최대 시간은 스케줄 락 lockAtMostFor(30분)보다
 * 충분히 짧은 20분 이하.
 */
class RetentionPropertiesTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RetentionProperties.class)
	static class Config {

	}

	private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@Test
	@DisplayName("TASK-27#1 기본값: 1시간마다, refresh 7일, OTP 1일, 배치 1000, 표마다 50000건, 10분")
	void defaults() {
		context.run(ctx -> {
			RetentionProperties properties = ctx.getBean(RetentionProperties.class);
			assertThat(properties.interval()).isEqualTo(Duration.ofHours(1));
			assertThat(properties.refreshTokenRetention()).isEqualTo(Duration.ofDays(7));
			assertThat(properties.otpRetention()).isEqualTo(Duration.ofDays(1));
			assertThat(properties.batchSize()).isEqualTo(1000);
			assertThat(properties.maxItems()).isEqualTo(50000);
			assertThat(properties.maxDuration()).isEqualTo(Duration.ofMinutes(10));
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "app.retention.refresh-token-retention=PT0S", "app.retention.otp-retention=-PT1H",
			"app.retention.interval=PT0S", "app.retention.batch-size=0", "app.retention.batch-size=10001",
			"app.retention.max-items=0", "app.retention.max-duration=PT0S", "app.retention.max-duration=PT21M" })
	@DisplayName("TASK-27#1 잘못된 값이면 기동하지 않는다")
	void invalid(String property) {
		context.withPropertyValues(property).run(ctx -> assertThat(ctx).hasFailed());
	}

}
