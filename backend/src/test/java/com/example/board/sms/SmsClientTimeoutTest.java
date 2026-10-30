package com.example.board.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/**
 * TASK-25#4 BE-24 End User Messaging 클라이언트의 호출 전체(apiCallTimeout)·시도별(apiCallAttemptTimeout) 타임아웃. 기본값·설정·검증과,
 * 응답하지 않는 로컬 HTTP 서버에 대해 발송이 기존 실패 계약(SmsDeliveryException → 503 sms_unavailable)으로 제한 시간 안에 끝나는지 본다.
 */
class SmsClientTimeoutTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(SmsConfiguration.class);

	private static ClientOverrideConfiguration overrides(PinpointSmsVoiceV2Client client) {
		return client.serviceClientConfiguration().overrideConfiguration();
	}

	@Test
	@DisplayName("TASK-25#4 기본값: 호출 전체 10초, 시도별 4초")
	void defaults() {
		context.withPropertyValues("app.sms.provider=aws").run(ctx -> {
			ClientOverrideConfiguration config = overrides(ctx.getBean(PinpointSmsVoiceV2Client.class));
			assertThat(config.apiCallTimeout()).contains(Duration.ofSeconds(10));
			assertThat(config.apiCallAttemptTimeout()).contains(Duration.ofSeconds(4));
		});
	}

	@Test
	@DisplayName("TASK-25#4 app.sms.aws.api-call-timeout·api-call-attempt-timeout 으로 바꾼다")
	void configurable() {
		context
			.withPropertyValues("app.sms.provider=aws", "app.sms.aws.api-call-timeout=6s",
					"app.sms.aws.api-call-attempt-timeout=2s")
			.run(ctx -> {
				ClientOverrideConfiguration config = overrides(ctx.getBean(PinpointSmsVoiceV2Client.class));
				assertThat(config.apiCallTimeout()).contains(Duration.ofSeconds(6));
				assertThat(config.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
			});
	}

	@Test
	@DisplayName("TASK-25#4 0·음수, 또는 시도별이 전체보다 길면 기동하지 않는다")
	void invalid() {
		context.withPropertyValues("app.sms.aws.api-call-timeout=0s").run(ctx -> assertThat(ctx).hasFailed());
		context.withPropertyValues("app.sms.aws.api-call-attempt-timeout=-1s").run(ctx -> assertThat(ctx).hasFailed());
		context
			.withPropertyValues("app.sms.aws.api-call-timeout=3s", "app.sms.aws.api-call-attempt-timeout=5s")
			.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	@DisplayName("TASK-25#4 End User Messaging 이 응답하지 않으면 호출 전체 타임아웃 안에 SmsDeliveryException(→ 503 sms_unavailable)")
	void timesOut() throws IOException, InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", exchange -> {
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		server.start();
		SmsProperties.Aws aws = new SmsProperties.Aws("us-east-1", null, null, Duration.ofMillis(1500),
				Duration.ofMillis(500));
		try (PinpointSmsVoiceV2Client client = SmsConfiguration.clientBuilder(aws)
			.endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			Instant start = Instant.now();

			assertThatThrownBy(() -> new EndUserMessagingSmsSender(client, aws).send("+821012345678", "x"))
				.isInstanceOf(SmsDeliveryException.class)
				.satisfies(ex -> assertThat(ex.getCause())
					.isInstanceOfAny(ApiCallTimeoutException.class, ApiCallAttemptTimeoutException.class));
			assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
		}
		finally {
			release.countDown();
			server.stop(0);
		}
	}

}
