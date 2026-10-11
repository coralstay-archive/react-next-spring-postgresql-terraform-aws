package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** TASK-11#1 업로드 설정: 버킷이 없으면 S3 클라이언트를 만들지 않고 앱은 그대로 뜬다(presign 은 503 uploads_unavailable). */
class UploadConfigurationTest {

	@Configuration(proxyBeanMethods = false)
	static class ClockConfig {

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(UploadConfiguration.class, ClockConfig.class);

	@Test
	@DisplayName("TASK-11#1 app.uploads.bucket 이 비어 있으면 S3 클라이언트 없이 비활성 저장소로 뜬다")
	void disabledWithoutBucket() {
		runner.withPropertyValues("app.uploads.bucket=", "app.uploads.region=us-east-1").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(S3Client.class).doesNotHaveBean(S3Presigner.class);
			UploadStorage storage = context.getBean(UploadStorage.class);
			assertThat(storage.available()).isFalse();
			assertThatThrownBy(() -> storage.presignPut("uploads/1/x.png", "image/png", 1, Duration.ofMinutes(5)))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(ex.getError()).isEqualTo("uploads_unavailable");
				});
		});
	}

	@Test
	@DisplayName("TASK-11#1 버킷이 있으면 S3 저장소를 만든다(기동 시 네트워크 호출 없음). 기본값: presign 5분, public-base-path /uploads/")
	void enabledWithBucket() {
		runner.withPropertyValues("app.uploads.bucket=b", "app.uploads.region=ap-northeast-2").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(UploadStorage.class).available()).isTrue();
			UploadProperties properties = context.getBean(UploadProperties.class);
			assertThat(properties.presignTtl()).isEqualTo(Duration.ofMinutes(5));
			assertThat(properties.publicBasePath()).isEqualTo("/uploads/");
			assertThat(properties.pendingTtl()).isEqualTo(Duration.ofHours(24));
		});
	}

	@Test
	@DisplayName("TASK-11#1 공개 URL: public-base-path + uploads/ 뒤의 키 (CloudFront /uploads/* 가 같은 키로 S3 를 읽는다)")
	void publicUrl() {
		assertThat(UploadProperties.publicUrl("/uploads/", "uploads/7/a.png")).isEqualTo("/uploads/7/a.png");
		assertThat(UploadProperties.publicUrl("/uploads", "uploads/7/a.png")).isEqualTo("/uploads/7/a.png");
		assertThat(UploadProperties.publicUrl("http://localhost:9000/board-uploads/uploads/", "uploads/7/a.png"))
			.isEqualTo("http://localhost:9000/board-uploads/uploads/7/a.png");
	}

}
