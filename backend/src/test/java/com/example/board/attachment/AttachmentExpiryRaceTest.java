package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MinIOContainer;

import com.example.board.TestcontainersConfiguration;
import com.example.board.attachment.StorageProbe.Op;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/**
 * TASK-26#2 BE-33 pending-ttl 이 지난 confirm 은 410 {@code attachment_expired}, 정리 작업은 행을 먼저 지운 뒤 객체를 지우고, confirm 의
 * markConfirmed 가 0행(정리·만료와 경쟁)이면 태그를 지운 객체를 정리한다.
 * TASK-26#B1 CONFIRMED 첨부는 항상 실제 객체가 있다 — confirm 과 정리가 어떤 순서로 겹쳐도.
 */
@IntegrationTest
class AttachmentExpiryRaceTest {

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
	StorageProbe probe;

	@Autowired
	PendingAttachmentCleanup cleanup;

	UploadClient uploads;

	S3Client s3;

	@BeforeEach
	void setUp() {
		uploads = new UploadClient(new Api(port));
		s3 = UploadClient.s3(minio);
	}

	@AfterEach
	void tearDown() {
		probe.reset();
		s3.close();
	}

	private JsonNode uploaded(String bearer) {
		JsonNode presigned = uploads.presigned(bearer, "image/png", 10);
		assertThat(UploadClient.put(presigned, UploadClient.bytes(10)).statusCode()).isEqualTo(200);
		return presigned;
	}

	private void age(long id, Duration age) {
		jdbc.update("update attachments set created_at = ? where id = ?", Timestamp.from(Instant.now().minus(age)), id);
	}

	private String status(long id) {
		List<String> status = jdbc.queryForList("select status from attachments where id = ?", String.class, id);
		return status.isEmpty() ? null : status.get(0);
	}

	private boolean objectExists(String key) {
		try {
			s3.headObject(b -> b.bucket(BUCKET).key(key));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
	}

	private static void assertExpired(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(410);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("attachment_expired");
	}

	/** B1: CONFIRMED 행의 객체는 모두 있다. */
	private void assertConfirmedHaveObjects(long... ids) {
		for (long id : ids) {
			if ("CONFIRMED".equals(status(id))) {
				String key = jdbc.queryForObject("select s3_key from attachments where id = ?", String.class, id);
				assertThat(objectExists(key)).as("CONFIRMED 첨부 %d 의 객체", id).isTrue();
			}
		}
	}

	@Test
	@DisplayName("TASK-26#2 pending-ttl(24h)이 지난 첨부의 confirm 은 410 attachment_expired 이고 S3 를 부르지 않는다. 첨부는 정리 대상으로 남는다")
	void expiredConfirmRejected() {
		String bearer = fixtures.bearer(fixtures.user());
		long id = uploaded(bearer).get("attachmentId").asLong();
		age(id, Duration.ofHours(25));
		AtomicBoolean headCalled = new AtomicBoolean();
		probe.before(Op.HEAD, key -> headCalled.set(true));

		assertExpired(uploads.confirm(bearer, id));

		assertThat(headCalled).isFalse();
		assertThat(status(id)).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("TASK-26#2 TASK-26#B1 confirm 이 태그를 지운 사이 정리 작업이 행을 지우면(markConfirmed 0행) 객체도 정리하고 410 attachment_expired")
	void cleanupWinsDuringConfirm() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		probe.after(Op.CLEAR_TAG, k -> jdbc.update("delete from attachments where id = ?", id));

		assertExpired(uploads.confirm(bearer, id));

		assertThat(status(id)).isNull();
		assertThat(objectExists(key)).as("태그 없는 영구 고아 객체가 남지 않는다").isFalse();
	}

	@Test
	@DisplayName("TASK-26#2 TASK-26#B1 confirm 도중 pending-ttl 이 지나면(아직 정리 전) markConfirmed 가 거부하고 객체를 정리, 410")
	void expiresDuringConfirm() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		probe.after(Op.CLEAR_TAG, k -> age(id, Duration.ofHours(25)));

		assertExpired(uploads.confirm(bearer, id));

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(objectExists(key)).isFalse();
	}

	@Test
	@DisplayName("TASK-26#2 TASK-26#B1 동시 confirm 이 먼저 확정해 markConfirmed 가 0행이면 200 CONFIRMED 이고 객체는 지우지 않는다")
	void concurrentConfirmKeepsObject() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		probe.after(Op.CLEAR_TAG, k -> jdbc.update(
				"update attachments set status = 'CONFIRMED', confirmed_at = now() where id = ? and status = 'PENDING'", id));

		HttpResponse<String> response = uploads.confirm(bearer, id);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("status").asString()).isEqualTo("CONFIRMED");
		assertThat(objectExists(key)).isTrue();
		assertConfirmedHaveObjects(id);
	}

	@Test
	@DisplayName("TASK-26#2 TASK-26#B1 정리는 행을 먼저 지우고 그다음 객체를 지운다(객체를 지울 때 그 행은 이미 없다)")
	void cleanupDeletesRowBeforeObject() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		age(id, Duration.ofHours(25));
		List<String> rowsAtDelete = new ArrayList<>();
		probe.before(Op.DELETE, k -> {
			if (k.equals(key)) {
				rowsAtDelete.add(String.valueOf(status(id)));
			}
		});

		cleanup.run();

		assertThat(rowsAtDelete).containsExactly("null");
		assertThat(objectExists(key)).isFalse();
	}

	@Test
	@DisplayName("TASK-26#2 TASK-26#B1 정리가 대상을 고른 뒤 confirm 이 먼저 확정하면 그 행과 객체는 남는다")
	void confirmWinsDuringCleanup() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode first = uploaded(bearer);
		JsonNode second = uploaded(bearer);
		long firstId = first.get("attachmentId").asLong();
		long secondId = second.get("attachmentId").asLong();
		String firstKey = first.get("key").asString();
		String secondKey = second.get("key").asString();
		age(firstId, Duration.ofHours(25));
		age(secondId, Duration.ofHours(25));
		// 첫 객체를 지우는 시점(둘 다 대상으로 고른 뒤)에 둘째 첨부의 confirm 이 커밋됐다고 친다
		probe.before(Op.DELETE, k -> {
			if (k.equals(firstKey)) {
				jdbc.update("update attachments set status = 'CONFIRMED', confirmed_at = now() where id = ?", secondId);
			}
		});

		cleanup.run();

		assertThat(status(firstId)).isNull();
		assertThat(objectExists(firstKey)).isFalse();
		assertThat(status(secondId)).isEqualTo("CONFIRMED");
		assertThat(objectExists(secondKey)).isTrue();
		assertConfirmedHaveObjects(firstId, secondId);
	}

}
