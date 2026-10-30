package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * TASK-26#3 BE-35 첨부 S3 클라이언트의 호출 전체(apiCallTimeout)·시도별(apiCallAttemptTimeout) 타임아웃 (TASK-25#4 SMS 와 같은 방식).
 * 기본값·설정·검증과, 응답하지 않는 S3 에 대해 confirm 의 HeadObject 가 503 {@code storage_unavailable} 로 제한 시간 안에 끝나는지 본다.
 */
class S3ClientTimeoutTest {

	@Configuration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(UploadConfiguration.class, ClockConfig.class)
		.withPropertyValues("app.uploads.bucket=b", "app.uploads.region=us-east-1");

	private static ClientOverrideConfiguration overrides(S3Client client) {
		return client.serviceClientConfiguration().overrideConfiguration();
	}

	@Test
	@DisplayName("TASK-26#3 기본값: 호출 전체 5초, 시도별 2초")
	void defaults() {
		runner.run(context -> {
			UploadProperties properties = context.getBean(UploadProperties.class);
			assertThat(properties.apiCallTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(2));
			ClientOverrideConfiguration config = overrides(context.getBean(UploadConfiguration.UploadStorageHolder.class).s3());
			assertThat(config.apiCallTimeout()).contains(Duration.ofSeconds(5));
			assertThat(config.apiCallAttemptTimeout()).contains(Duration.ofSeconds(2));
		});
	}

	@Test
	@DisplayName("TASK-26#3 app.uploads.api-call-timeout·api-call-attempt-timeout 으로 바꾼다")
	void configurable() {
		runner.withPropertyValues("app.uploads.api-call-timeout=8s", "app.uploads.api-call-attempt-timeout=3s")
			.run(context -> {
				ClientOverrideConfiguration config = overrides(
						context.getBean(UploadConfiguration.UploadStorageHolder.class).s3());
				assertThat(config.apiCallTimeout()).contains(Duration.ofSeconds(8));
				assertThat(config.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3));
			});
	}

	@Test
	@DisplayName("TASK-26#3 0·음수, 또는 시도별이 전체보다 길면 기동하지 않는다")
	void invalid() {
		runner.withPropertyValues("app.uploads.api-call-timeout=0s").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.uploads.api-call-attempt-timeout=-1s")
			.run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("app.uploads.api-call-timeout=2s", "app.uploads.api-call-attempt-timeout=3s")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("TASK-26#3 S3 가 응답하지 않으면 HeadObject 가 호출 전체 타임아웃 안에 503 storage_unavailable(원인 ApiCall*TimeoutException)")
	void timesOut() throws IOException {
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
		UploadProperties properties = new UploadProperties("b", "us-east-1",
				"http://127.0.0.1:" + server.getAddress().getPort(), true, "test", "test", "/uploads/",
				Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofMillis(1500), Duration.ofMillis(500),
				Duration.ofDays(7), 5000, Duration.ofMinutes(10));
		try (S3Client client = UploadConfiguration.s3ClientBuilder(properties,
				StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build()) {
			S3UploadStorage storage = new S3UploadStorage(client, null, "b");
			Instant start = Instant.now();

			assertThatThrownBy(() -> storage.head("uploads/1/x.png")).isInstanceOf(StorageUnavailableException.class)
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
