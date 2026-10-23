package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MinIOContainer;

import com.example.board.TestcontainersConfiguration;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.RowLock;
import com.example.board.support.UploadClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * TASK-26#4 지연 삭제의 조건부 DELETE 가 동시 다시 붙이기·복구와 겹칠 때. 테스트 커넥션이 다시 붙이기(글 수정이 첨부 행을 FOR UPDATE 로 잠그고
 * 바꾼다)나 복구(글 행 UPDATE)를 커밋 전 상태로 쥐고 있는 동안 정리의 DELETE 가 그 잠금 앞에서 기다리는 것을 {@code pg_stat_activity} 로
 * 확인한 뒤 커밋한다 → DELETE 는 바뀐 행으로 조건을 다시 보고 0행이고 객체는 남는다(TASK-26 B1·B2).
 */
@IntegrationTest
@TestPropertySource(properties = { "app.db.lock-timeout=" + RowLock.LOCK_TIMEOUT, "app.db.statement-timeout=" + RowLock.LOCK_TIMEOUT,
		"app.db.transaction-timeout=" + RowLock.TRANSACTION_TIMEOUT,
		"spring.datasource.hikari.data-source-properties.socketTimeout=" + RowLock.SOCKET_TIMEOUT_SECONDS })
class OrphanAttachmentCleanupRaceTest {

	static final String BUCKET = TestcontainersConfiguration.UPLOADS_BUCKET;

	static final String CLEANUP_DELETE = "delete from attachments";

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	@Autowired
	OrphanAttachmentCleanup cleanup;

	@Autowired
	AttachmentRepository attachments;

	Api api;

	UploadClient uploads;

	S3Client s3;

	ExecutorService pool;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
		s3 = UploadClient.s3(minio);
		pool = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
		s3.close();
	}

	private long post(String bearer, long attachmentId) {
		HttpResponse<String> response = api.post("/posts").bearer(bearer)
			.json("{\"title\":\"사진\",\"content\":\"본문\",\"attachmentIds\":[" + attachmentId + "]}").send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return AuthClient.json(response).get("id").asLong();
	}

	private long deletedPostWithAttachment(String bearer, long attachment, Duration age) {
		long postId = post(bearer, attachment);
		assertThat(api.delete("/posts/" + postId).bearer(bearer).send().statusCode()).isEqualTo(204);
		jdbc.update("update posts set deleted_at = ? where id = ?", ago(age), postId);
		return postId;
	}

	private String key(long attachmentId) {
		return jdbc.queryForObject("select s3_key from attachments where id = ?", String.class, attachmentId);
	}

	private Long postIdOf(long attachmentId) {
		return jdbc.queryForObject("select post_id from attachments where id = ?", Long.class, attachmentId);
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

	private static Timestamp ago(Duration age) {
		return Timestamp.from(Instant.now().minus(age));
	}

	@Test
	@DisplayName("TASK-26#4 TASK-26#B1 정리 DELETE 가 다시 붙이기(첨부 행 FOR UPDATE + post_id 설정)를 기다렸다가 커밋 뒤 0행 → 행·객체가 남는다")
	void reattachWinsOverCleanup() throws Exception {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = post(bearer, attachment);
		assertThat(api.patch("/posts/" + postId).bearer(bearer).json("{\"attachmentIds\":[]}").send().statusCode())
			.isEqualTo(200);
		jdbc.update("update attachments set detached_at = ? where id = ?", ago(Duration.ofDays(8)), attachment);

		Future<Integer> swept;
		try (RowLock lock = RowLock.hold(dataSource, "select id from attachments where id = ? for update", attachment)
			.execute("update attachments set post_id = ?, detached_at = null where id = ?", postId, attachment)) {
			swept = pool.submit(cleanup::cleanUp);
			lock.awaitWaiters(1, CLEANUP_DELETE);
			lock.commit();
		}
		swept.get(30, TimeUnit.SECONDS);

		assertThat(postIdOf(attachment)).as("다시 붙인 첨부는 지우지 않는다").isEqualTo(postId);
		assertThat(objectExists(key)).isTrue();
	}

	@Test
	@DisplayName("TASK-26#4 TASK-26#B2 정리 DELETE 가 관리자 복구(글 행 UPDATE deleted_at=null)를 FOR SHARE 로 기다렸다가 커밋 뒤 0행 → 행·객체가 남는다")
	void restoreWinsOverCleanup() throws Exception {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = deletedPostWithAttachment(bearer, attachment, Duration.ofDays(8));

		Future<Integer> swept;
		try (RowLock lock = RowLock.hold(dataSource, "select id from posts where id = ? for update", postId)
			.execute("update posts set deleted_at = null where id = ?", postId)) {
			swept = pool.submit(cleanup::cleanUp);
			lock.awaitWaiters(1, CLEANUP_DELETE);
			lock.commit();
		}
		swept.get(30, TimeUnit.SECONDS);

		assertThat(postIdOf(attachment)).as("복구한 글의 첨부는 지우지 않는다").isEqualTo(postId);
		assertThat(objectExists(key)).isTrue();
	}

	@Test
	@DisplayName("TASK-26#4 정리 DELETE 는 대상 첨부의 글 한 행만 FOR SHARE 로 잠근다 — 다른 오래된 삭제 글이 잠겨 있어도 기다리지 않는다")
	void deleteLocksOnlyItsOwnPost() throws Exception {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		deletedPostWithAttachment(bearer, attachment, Duration.ofDays(8));
		long other = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long otherPost = deletedPostWithAttachment(bearer, other, Duration.ofDays(9));
		Instant cutoff = Instant.now().minus(Duration.ofDays(7));

		try (RowLock lock = RowLock.hold(dataSource, "select id from posts where id = ? for update", otherPost)) {
			Future<Integer> deleted = pool.submit(() -> attachments.deleteIfOrphanedBefore(attachment, cutoff));
			assertThat(deleted.get(10, TimeUnit.SECONDS)).as("잠긴 다른 글을 기다리지 않고 지운다").isEqualTo(1);
		}
	}

}
