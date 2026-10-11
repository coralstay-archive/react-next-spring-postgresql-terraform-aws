package com.example.board;

import java.io.IOException;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/**
	 * 첨부 업로드용 S3 호환 저장소 (TASK-11#3). Docker Hub 의 minio/minio 이미지는 더 이상 받을 수 없어 Chainguard 빌드를 쓴다
	 * (docker-compose.yml 과 같은 이미지·digest).
	 */
	public static final String MINIO_IMAGE = "cgr.dev/chainguard/minio@sha256:7df55d002b698e4494a131b848dd553361bbe58b7e19f62d9623c29af1ba9ccf";

	/** 테스트 버킷. */
	public static final String UPLOADS_BUCKET = "board-test-uploads";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

	/**
	 * TASK-11#3 MinIO 컨테이너 하나를 모든 통합 테스트가 같이 쓴다(PostgreSQL 과 같은 방식). 버킷은 docker-compose 의 init 처럼
	 * 컨테이너 안의 mc 로 만든다.
	 */
	@Bean
	MinIOContainer minioContainer() throws IOException, InterruptedException {
		MinIOContainer minio = new MinIOContainer(
				DockerImageName.parse(MINIO_IMAGE).asCompatibleSubstituteFor("minio/minio"));
		minio.start();
		ExecResult result = minio.execInContainer("sh", "-c", "mc alias set local http://127.0.0.1:9000 \"$0\" \"$1\" >/dev/null"
				+ " && mc mb --ignore-existing local/" + UPLOADS_BUCKET, minio.getUserName(), minio.getPassword());
		if (result.getExitCode() != 0) {
			throw new IllegalStateException("MinIO 버킷 생성 실패: " + result.getStdout() + result.getStderr());
		}
		return minio;
	}

	/** 앱의 업로드 설정을 MinIO 로 향하게 한다(path-style, 정적 자격 증명). */
	@Bean
	DynamicPropertyRegistrar minioUploadProperties(MinIOContainer minio) {
		return registry -> {
			registry.add("app.uploads.bucket", () -> UPLOADS_BUCKET);
			registry.add("app.uploads.region", () -> "us-east-1");
			registry.add("app.uploads.endpoint", minio::getS3URL);
			registry.add("app.uploads.path-style", () -> "true");
			registry.add("app.uploads.access-key", minio::getUserName);
			registry.add("app.uploads.secret-key", minio::getPassword);
		};
	}

}
