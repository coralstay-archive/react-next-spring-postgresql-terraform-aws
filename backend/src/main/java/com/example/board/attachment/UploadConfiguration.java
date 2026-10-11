package com.example.board.attachment;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 첨부 저장소 (TASK-11#1). {@code app.uploads.bucket} 이 있으면 S3 저장소, 없으면 비활성 저장소(앱은 그대로 뜨고 업로드는 503).
 *
 * <p>S3 클라이언트는 이 빈 안에서만 만든다(다른 빈으로 노출하지 않는다 — 버킷이 없을 때 S3 클라이언트가 생기지 않게). 기동 시 네트워크를
 * 쓰지 않는다. 자격 증명은 {@code access-key} 가 있으면 정적 값(로컬 MinIO), 없으면 SDK 기본 체인(EC2 인스턴스 프로파일)이다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadProperties.class)
public class UploadConfiguration {

	@Bean(destroyMethod = "close")
	UploadStorageHolder uploadStorageHolder(UploadProperties properties) {
		if (!properties.enabled()) {
			return new UploadStorageHolder(new DisabledUploadStorage(), null, null);
		}
		Region region = Region.of(properties.region());
		AwsCredentialsProvider credentials = credentials(properties);
		S3Configuration serviceConfiguration = S3Configuration.builder()
			.pathStyleAccessEnabled(properties.pathStyle())
			.build();
		var client = S3Client.builder()
			.region(region)
			.credentialsProvider(credentials)
			.httpClient(UrlConnectionHttpClient.create())
			.serviceConfiguration(serviceConfiguration)
			// 체크섬은 필요할 때만(S3 호환 저장소와 맞추기 쉽게). 업로드 본문은 앱을 거치지 않는다.
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
		var presigner = S3Presigner.builder()
			.region(region)
			.credentialsProvider(credentials)
			.serviceConfiguration(serviceConfiguration);
		if (!properties.endpoint().isBlank()) {
			URI endpoint = URI.create(properties.endpoint());
			client.endpointOverride(endpoint);
			presigner.endpointOverride(endpoint);
		}
		S3Client s3 = client.build();
		S3Presigner s3Presigner = presigner.build();
		return new UploadStorageHolder(new S3UploadStorage(s3, s3Presigner, properties.bucket()), s3, s3Presigner);
	}

	@Bean
	UploadStorage uploadStorage(UploadStorageHolder holder) {
		return holder.storage();
	}

	private static AwsCredentialsProvider credentials(UploadProperties properties) {
		if (!properties.accessKey().isBlank()) {
			return StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
		}
		return DefaultCredentialsProvider.builder().build();
	}

	/** 저장소와, 종료 때 닫을 S3 클라이언트들. */
	record UploadStorageHolder(UploadStorage storage, S3Client s3, S3Presigner presigner) implements AutoCloseable {

		@Override
		public void close() {
			if (presigner != null) {
				presigner.close();
			}
			if (s3 != null) {
				s3.close();
			}
		}

	}

}
