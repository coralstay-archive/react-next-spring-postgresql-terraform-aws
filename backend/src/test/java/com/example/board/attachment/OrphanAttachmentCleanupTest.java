package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/**
 * TASK-26#4 BE-36 글에서 뗀 첨부(수정으로 빠짐)·글에 붙이지 않은 확정 첨부·삭제된 글의 첨부는 유예 기간(기본 7일,
 * {@code app.uploads.orphan-grace-period}) 뒤 행을 지우고 객체를 지운다. 유예 안에 다시 붙이거나 글을 복구하면 그대로다.
 * TASK-26#B2 삭제된 글의 이미지는 정책 기간 뒤 공개 URL(CloudFront /uploads/* → S3 의 같은 키)로 열리지 않는다 — 원본 객체가 없다.
 */
@IntegrationTest
class OrphanAttachmentCleanupTest {

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
	OrphanAttachmentCleanup cleanup;

	@Autowired
	AttachmentRepository attachmentRepository;

	@Autowired
	UploadStorage storage;

	@Autowired
	UploadProperties properties;

	Api api;

	UploadClient uploads;

	S3Client s3;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
		s3 = UploadClient.s3(minio);
		// 배포(V15) 직후의 유예는 따로 시험한다(AttachmentCleanupMigrationTest·startGrace). 여기서는 오래전에 배포한 것으로 둔다
		jdbc.update("update attachment_cleanup_start set started_at = ?", ago(Duration.ofDays(365)));
	}

	@AfterEach
	void tearDown() {
		s3.close();
	}

	private long post(String bearer, long attachmentId) {
		HttpResponse<String> response = api.post("/posts").bearer(bearer)
			.json("{\"title\":\"사진\",\"content\":\"본문\",\"attachmentIds\":[" + attachmentId + "]}").send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return AuthClient.json(response).get("id").asLong();
	}

	private void patchAttachments(String bearer, long postId, String ids) {
		HttpResponse<String> response = api.patch("/posts/" + postId).bearer(bearer)
			.json("{\"attachmentIds\":" + ids + "}").send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
	}

	private String key(long attachmentId) {
		return jdbc.queryForObject("select s3_key from attachments where id = ?", String.class, attachmentId);
	}

	private boolean rowExists(long attachmentId) {
		return jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, attachmentId) == 1;
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

	private void ageDetached(long attachmentId, Duration age) {
		assertThat(jdbc.update("update attachments set detached_at = ? where id = ? and detached_at is not null", ago(age),
				attachmentId))
			.as("연결 해제 시각이 기록돼 있다")
			.isEqualTo(1);
	}

	private void ageConfirmed(long attachmentId, Duration age) {
		jdbc.update("update attachments set created_at = ?, confirmed_at = ? where id = ?", ago(age), ago(age),
				attachmentId);
	}

	private void ageDeleted(long postId, Duration age) {
		jdbc.update("update posts set deleted_at = ? where id = ?", ago(age), postId);
	}

	private List<Long> detailAttachments(String bearer, long postId) {
		HttpResponse<String> response = api.get("/posts/" + postId).bearer(bearer).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response).get("attachments").valueStream().map(a -> a.get("id").asLong()).toList();
	}

	@Test
	@DisplayName("TASK-26#4 수정으로 글에서 뗀 첨부는 유예 기간(7일) 안에는 남고, 지나면 행과 객체를 지운다")
	void detachedAfterGrace() {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = post(bearer, attachment);
		ageConfirmed(attachment, Duration.ofDays(30));
		patchAttachments(bearer, postId, "[]");

		ageDetached(attachment, Duration.ofDays(6));
		cleanup.run();
		assertThat(rowExists(attachment)).isTrue();
		assertThat(objectExists(key)).isTrue();

		ageDetached(attachment, Duration.ofDays(8));
		cleanup.run();
		assertThat(rowExists(attachment)).isFalse();
		assertThat(objectExists(key)).isFalse();
	}

	@Test
	@DisplayName("TASK-26#4 뗀 첨부를 유예 안에 다시 붙이면 연결 해제 기록이 지워져 지우지 않는다")
	void reattachedKept() {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long postId = post(bearer, attachment);
		patchAttachments(bearer, postId, "[]");
		ageDetached(attachment, Duration.ofDays(6));
		patchAttachments(bearer, postId, "[" + attachment + "]");
		ageConfirmed(attachment, Duration.ofDays(30));

		cleanup.run();

		assertThat(jdbc.queryForObject("select detached_at is null from attachments where id = ?", Boolean.class,
				attachment))
			.isTrue();
		assertThat(rowExists(attachment)).isTrue();
		assertThat(objectExists(key(attachment))).isTrue();
	}

	@Test
	@DisplayName("TASK-26#4 확정했지만 글에 붙이지 않은 첨부는 확정 시각부터 유예 기간 뒤 지운다")
	void neverAttachedAfterGrace() {
		String bearer = fixtures.bearer(fixtures.user());
		long stale = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String staleKey = key(stale);
		ageConfirmed(stale, Duration.ofDays(8));
		long fresh = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		ageConfirmed(fresh, Duration.ofDays(6));

		cleanup.run();

		assertThat(rowExists(stale)).isFalse();
		assertThat(objectExists(staleKey)).isFalse();
		assertThat(rowExists(fresh)).isTrue();
		assertThat(objectExists(key(fresh))).isTrue();
	}

	@Test
	@DisplayName("TASK-26#4 TASK-26#B2 삭제된 글의 첨부는 유예 기간 뒤 행·객체를 지운다 → 공개 URL 의 원본(S3 같은 키)이 없다")
	void deletedPostAfterGrace() {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = post(bearer, attachment);
		assertThat(api.delete("/posts/" + postId).bearer(bearer).send().statusCode()).isEqualTo(204);
		ageConfirmed(attachment, Duration.ofDays(30));

		ageDeleted(postId, Duration.ofDays(6));
		cleanup.run();
		assertThat(objectExists(key)).as("유예 안에는 남는다").isTrue();

		ageDeleted(postId, Duration.ofDays(8));
		cleanup.run();
		assertThat(rowExists(attachment)).isFalse();
		assertThat(objectExists(key)).isFalse();
	}

	@Test
	@DisplayName("TASK-26#4 TASK-26#B2 유예 기간 안에 관리자가 삭제 글을 복구하면 이미지가 그대로 붙어 있고 객체도 남는다")
	void restoredWithinGraceKeepsImages() {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = post(bearer, attachment);
		assertThat(api.delete("/posts/" + postId).bearer(bearer).send().statusCode()).isEqualTo(204);
		ageConfirmed(attachment, Duration.ofDays(30));
		ageDeleted(postId, Duration.ofDays(6));
		cleanup.run();

		HttpResponse<String> restored = api.post("/admin/posts/" + postId + "/restore")
			.bearer(fixtures.bearer(fixtures.admin()))
			.send();
		assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
		cleanup.run();

		assertThat(detailAttachments(bearer, postId)).containsExactly(attachment);
		assertThat(objectExists(key)).isTrue();
	}

	@Test
	@DisplayName("TASK-26#4 TASK-26#B2 배포(V15) 전에 삭제된 글의 첨부도 배포 시각부터 유예 기간을 준다 — 배포 직후 첫 정리에 지우지 않는다")
	void deletedBeforeDeployGetsGraceFromDeploy() {
		String bearer = fixtures.bearer(fixtures.user());
		long attachment = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		String key = key(attachment);
		long postId = post(bearer, attachment);
		assertThat(api.delete("/posts/" + postId).bearer(bearer).send().statusCode()).isEqualTo(204);
		ageConfirmed(attachment, Duration.ofDays(60));
		ageDeleted(postId, Duration.ofDays(30));

		jdbc.update("update attachment_cleanup_start set started_at = ?", ago(Duration.ofDays(6)));
		cleanup.run();
		assertThat(rowExists(attachment)).as("배포 6일째: 유예 안").isTrue();
		assertThat(objectExists(key)).isTrue();

		jdbc.update("update attachment_cleanup_start set started_at = ?", ago(Duration.ofDays(8)));
		cleanup.run();
		assertThat(rowExists(attachment)).as("배포 8일째: 유예 지남").isFalse();
		assertThat(objectExists(key)).isFalse();
	}

	private List<Long> staleUnlinked(int count) {
		String bearer = fixtures.bearer(fixtures.user());
		List<Long> ids = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			long id = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
			ageConfirmed(id, Duration.ofDays(8));
			ids.add(id);
		}
		return ids;
	}

	@Test
	@DisplayName("TASK-26#4 실행당 건수 예산(app.uploads.cleanup-max-items)을 넘으면 멈추고 남은 것은 다음 실행이 지운다")
	void itemBudgetPerRun() {
		cleanup.cleanUp();
		List<Long> stale = staleUnlinked(3);
		OrphanAttachmentCleanup budgeted = new OrphanAttachmentCleanup(attachmentRepository, storage,
				CleanupBudgets.withBudget(properties, 2, Duration.ofMinutes(10)), java.time.Clock.systemUTC());

		assertThat(budgeted.cleanUp()).isEqualTo(2);
		assertThat(stale.stream().filter(this::rowExists).count()).as("하나는 다음 실행으로").isEqualTo(1);

		assertThat(budgeted.cleanUp()).isEqualTo(1);
		assertThat(stale.stream().filter(this::rowExists).count()).isZero();
	}

	@Test
	@DisplayName("TASK-26#4 실행당 시간 예산(app.uploads.cleanup-max-duration)이 지나면 멈추고 남은 것은 다음 실행이 지운다")
	void durationBudgetPerRun() {
		cleanup.cleanUp();
		List<Long> stale = staleUnlinked(3);
		// 시계를 읽을 때마다 4분: 시작 → 4분(1건) → 8분(2건) → 12분(10분 예산 넘음, 멈춤)
		OrphanAttachmentCleanup budgeted = new OrphanAttachmentCleanup(attachmentRepository, storage,
				CleanupBudgets.withBudget(properties, 1000, Duration.ofMinutes(10)),
				CleanupBudgets.stepping(Duration.ofMinutes(4)));

		assertThat(budgeted.cleanUp()).isEqualTo(2);
		assertThat(stale.stream().filter(this::rowExists).count()).isEqualTo(1);

		cleanup.cleanUp();
		assertThat(stale.stream().filter(this::rowExists).count()).isZero();
	}

	@Test
	@DisplayName("TASK-26#4 정리는 주기적으로 돈다(@Scheduled, app.uploads.cleanup-interval)")
	void scheduled() throws NoSuchMethodException {
		Scheduled scheduled = OrphanAttachmentCleanup.class.getMethod("run").getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString()).contains("app.uploads.cleanup-interval");
	}

}
