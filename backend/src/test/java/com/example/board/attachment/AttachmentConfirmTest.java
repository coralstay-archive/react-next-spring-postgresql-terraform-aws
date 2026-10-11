package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MinIOContainer;

import com.example.board.TestcontainersConfiguration;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;
import com.example.board.user.User;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/** TASK-11#2 POST /attachments/{id}/confirm: 본인만, HeadObject 로 존재·크기·타입을 다시 확인하고 pending 태그를 지운다. */
@IntegrationTest
class AttachmentConfirmTest {

	static final String BUCKET = TestcontainersConfiguration.UPLOADS_BUCKET;

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	S3Client s3;

	Api api;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
		s3 = UploadClient.s3(minio);
	}

	@AfterEach
	void tearDown() {
		s3.close();
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	private String status(long id) {
		return jdbc.queryForObject("select status from attachments where id = ?", String.class, id);
	}

	@Test
	@DisplayName("TASK-11#2 업로드 후 confirm: 200 + CONFIRMED + 공개 url, 객체의 status=pending 태그가 지워진다(lifecycle 대상 아님)")
	void confirm() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		JsonNode presigned = uploads.presigned(bearer, "image/jpeg", 3000);
		assertThat(UploadClient.put(presigned, UploadClient.bytes(3000)).statusCode()).isEqualTo(200);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();

		HttpResponse<String> response = uploads.confirm(bearer, id);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("status").asString()).isEqualTo("CONFIRMED");
		assertThat(body.get("contentType").asString()).isEqualTo("image/jpeg");
		assertThat(body.get("size").asLong()).isEqualTo(3000L);
		assertThat(body.get("url").asString()).isEqualTo("/uploads/" + key.substring("uploads/".length()));

		assertThat(status(id)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject("select confirmed_at is not null from attachments where id = ?", Boolean.class, id))
			.isTrue();
		assertThat(s3.getObjectTagging(b -> b.bucket(BUCKET).key(key)).tagSet()).isEmpty();

		HttpResponse<String> again = uploads.confirm(bearer, id);
		assertThat(again.statusCode()).as("멱등").isEqualTo(200);
		assertThat(AuthClient.json(again).get("status").asString()).isEqualTo("CONFIRMED");
	}

	@Test
	@DisplayName("TASK-11#2 아직 올리지 않았으면 400 attachment_not_uploaded, 첨부는 PENDING 그대로")
	void notUploaded() {
		String bearer = fixtures.bearer(fixtures.user());
		long id = uploads.presigned(bearer, "image/png", 10).get("attachmentId").asLong();
		HttpResponse<String> response = uploads.confirm(bearer, id);
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(error(response)).isEqualTo("attachment_not_uploaded");
		assertThat(status(id)).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("TASK-11#2 저장된 객체의 타입·크기가 발급 때와 다르면 400 attachment_mismatch 이고 객체를 지운다")
	void mismatchDeletesObject() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode wrongType = uploads.presigned(bearer, "image/png", 10);
		String typeKey = wrongType.get("key").asString();
		// 서명을 우회해 저장소에 직접 넣은 경우(예: 자격 증명 유출)를 흉내 낸다
		s3.putObject(b -> b.bucket(BUCKET).key(typeKey).contentType("text/html"), RequestBody.fromBytes(UploadClient.bytes(10)));
		HttpResponse<String> typeResponse = uploads.confirm(bearer, wrongType.get("attachmentId").asLong());
		assertThat(typeResponse.statusCode()).isEqualTo(400);
		assertThat(error(typeResponse)).isEqualTo("attachment_mismatch");
		assertThatThrownBy(() -> s3.headObject(b -> b.bucket(BUCKET).key(typeKey))).isInstanceOf(NoSuchKeyException.class);
		assertThat(status(wrongType.get("attachmentId").asLong())).isEqualTo("PENDING");

		JsonNode wrongSize = uploads.presigned(bearer, "image/png", 10);
		String sizeKey = wrongSize.get("key").asString();
		s3.putObject(b -> b.bucket(BUCKET).key(sizeKey).contentType("image/png"), RequestBody.fromBytes(UploadClient.bytes(11)));
		HttpResponse<String> sizeResponse = uploads.confirm(bearer, wrongSize.get("attachmentId").asLong());
		assertThat(sizeResponse.statusCode()).isEqualTo(400);
		assertThat(error(sizeResponse)).isEqualTo("attachment_mismatch");
	}

	@Test
	@DisplayName("TASK-11#2 본인만: 남의 첨부는 403 attachment_forbidden(ADMIN 포함), 없는 id 는 404 attachment_not_found, 익명 401")
	void ownerOnly() {
		String ownerBearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploads.presigned(ownerBearer, "image/gif", 5);
		assertThat(UploadClient.put(presigned, UploadClient.bytes(5)).statusCode()).isEqualTo(200);
		long id = presigned.get("attachmentId").asLong();

		for (User other : new User[] { fixtures.user(), fixtures.admin() }) {
			HttpResponse<String> response = uploads.confirm(fixtures.bearer(other), id);
			assertThat(response.statusCode()).isEqualTo(403);
			assertThat(error(response)).isEqualTo("attachment_forbidden");
		}
		assertThat(status(id)).isEqualTo("PENDING");

		HttpResponse<String> missing = uploads.confirm(ownerBearer, Long.MAX_VALUE);
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(error(missing)).isEqualTo("attachment_not_found");

		String csrf = Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
		assertThat(api.post("/attachments/" + id + "/confirm").csrf(csrf).send().statusCode()).isEqualTo(401);
	}

}
