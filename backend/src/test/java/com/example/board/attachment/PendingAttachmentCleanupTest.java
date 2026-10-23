package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.testcontainers.containers.MinIOContainer;

import com.example.board.TestcontainersConfiguration;
import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/**
 * TASK-11#4 앱 측 미확정 정리: 24시간 넘게 PENDING 인 첨부 행을 지우고 객체도 지운다(best-effort — 남아도 S3 lifecycle 이 1일 뒤
 * 지운다). 확정된 첨부와 24시간이 안 된 PENDING 은 그대로.
 */
@IntegrationTest
class PendingAttachmentCleanupTest {

	static final String BUCKET = TestcontainersConfiguration.UPLOADS_BUCKET;

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	@Autowired
	PendingAttachmentCleanup cleanup;

	S3Client s3;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		uploads = new UploadClient(new Api(port));
		s3 = UploadClient.s3(minio);
	}

	@AfterEach
	void tearDown() {
		s3.close();
	}

	private void age(long id, Duration age) {
		jdbc.update("update attachments set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(age)), id);
	}

	private boolean exists(long id) {
		return jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, id) == 1;
	}

	@Test
	@DisplayName("TASK-11#4 24시간 넘은 PENDING 은 행과 객체를 지우고, 24시간 안 된 PENDING·오래된 CONFIRMED 는 남긴다")
	void cleansStalePending() {
		String bearer = fixtures.bearer(fixtures.user());

		JsonNode staleUploaded = uploads.presigned(bearer, "image/png", 10);
		assertThat(UploadClient.put(staleUploaded, UploadClient.bytes(10)).statusCode()).isEqualTo(200);
		long staleUploadedId = staleUploaded.get("attachmentId").asLong();
		String staleKey = staleUploaded.get("key").asString();
		age(staleUploadedId, Duration.ofHours(25));

		long staleNeverUploaded = uploads.presigned(bearer, "image/png", 10).get("attachmentId").asLong();
		age(staleNeverUploaded, Duration.ofHours(25));

		long fresh = uploads.presigned(bearer, "image/png", 10).get("attachmentId").asLong();
		age(fresh, Duration.ofHours(23));

		long confirmed = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		age(confirmed, Duration.ofDays(30));

		int removed = cleanup.cleanUp();

		assertThat(removed).isGreaterThanOrEqualTo(2);
		assertThat(exists(staleUploadedId)).isFalse();
		assertThat(exists(staleNeverUploaded)).isFalse();
		assertThat(exists(fresh)).isTrue();
		assertThat(exists(confirmed)).isTrue();
		assertThatThrownBy(() -> s3.headObject(b -> b.bucket(BUCKET).key(staleKey))).isInstanceOf(NoSuchKeyException.class);
	}

	@Test
	@DisplayName("TASK-11#4 정리는 주기적으로 돈다(@Scheduled, app.uploads.cleanup-interval)")
	void scheduled() throws NoSuchMethodException {
		Scheduled scheduled = PendingAttachmentCleanup.class.getMethod("run").getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString()).contains("app.uploads.cleanup-interval");
	}

}
