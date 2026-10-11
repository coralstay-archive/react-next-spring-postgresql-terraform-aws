package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;
import tools.jackson.databind.JsonNode;

/** TASK-11#1 POST /attachments/presign + TASK-11#B1 image/png·jpeg·webp·gif, 5MB 이하만. */
@IntegrationTest
class AttachmentPresignTest {

	static final long FIVE_MB = 5L * 1024 * 1024;

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

	private static Map<String, String> query(String url) {
		String query = url.substring(url.indexOf('?') + 1);
		return Arrays.stream(query.split("&")).map(p -> p.split("=", 2))
			.collect(java.util.stream.Collectors.toMap(p -> p[0],
					p -> URLDecoder.decode(p.length > 1 ? p[1] : "", StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("TASK-11#1 presign: PENDING 첨부 + uploads/{userId}/{uuid}.png 키 + 5분짜리 PUT URL + 보내야 할 헤더")
	void presign() {
		User user = fixtures.user();
		JsonNode body = uploads.presigned(fixtures.bearer(user), "image/png", 1234);

		long id = body.get("attachmentId").asLong();
		String key = body.get("key").asString();
		assertThat(key).matches("uploads/" + user.getId() + "/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png");

		String url = body.get("uploadUrl").asString();
		assertThat(url).contains("/" + TestcontainersConfiguration.UPLOADS_BUCKET + "/" + key + "?");
		Map<String, String> query = query(url);
		assertThat(query.get("X-Amz-Expires")).isEqualTo("300");
		assertThat(List.of(query.get("X-Amz-SignedHeaders").split(";"))).contains("content-length", "content-type",
				"host", "x-amz-tagging");
		assertThat(body.get("method").asString()).isEqualTo("PUT");
		assertThat(body.get("expiresAt").asString()).isNotBlank();

		Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		body.get("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
		assertThat(headers).containsEntry("content-type", "image/png").containsEntry("x-amz-tagging", "status=pending");
		assertThat(headers).doesNotContainKeys("host", "content-length");

		Map<String, Object> row = jdbc.queryForMap(
				"select owner_id, post_id, s3_key, content_type, size, status, confirmed_at from attachments where id = ?", id);
		assertThat(row.get("owner_id")).isEqualTo(user.getId());
		assertThat(row.get("post_id")).isNull();
		assertThat(row.get("s3_key")).isEqualTo(key);
		assertThat(row.get("content_type")).isEqualTo("image/png");
		assertThat(((Number) row.get("size")).longValue()).isEqualTo(1234L);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("confirmed_at")).isNull();
	}

	@Test
	@DisplayName("TASK-11#1 발급한 URL 로 실제 PUT 하면 객체가 생기고 status=pending 태그가 붙는다")
	void presignedPutStoresTaggedObject() {
		JsonNode body = uploads.presigned(fixtures.bearer(fixtures.user()), "image/webp", 2048);
		HttpResponse<String> put = UploadClient.put(body, UploadClient.bytes(2048));
		assertThat(put.statusCode()).as(put.body()).isEqualTo(200);

		String key = body.get("key").asString();
		var head = s3.headObject(b -> b.bucket(TestcontainersConfiguration.UPLOADS_BUCKET).key(key));
		assertThat(head.contentLength()).isEqualTo(2048L);
		assertThat(head.contentType()).isEqualTo("image/webp");
		List<Tag> tags = s3.getObjectTagging(b -> b.bucket(TestcontainersConfiguration.UPLOADS_BUCKET).key(key)).tagSet();
		assertThat(tags).extracting(Tag::key, Tag::value).containsExactly(org.assertj.core.groups.Tuple.tuple("status", "pending"));
	}

	@Test
	@DisplayName("TASK-11#B1 서명된 크기·타입과 다르게 올리면 저장소가 거부한다(서명 불일치 403)")
	void uploadMustMatchSignedSizeAndType() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode bigger = uploads.presigned(bearer, "image/png", 100);
		assertThat(UploadClient.put(bigger, UploadClient.bytes(101)).statusCode()).isEqualTo(403);

		JsonNode otherType = uploads.presigned(bearer, "image/png", 100);
		assertThat(UploadClient.put(otherType, UploadClient.bytes(100), Map.of("content-type", "text/html")).statusCode())
			.isEqualTo(403);

		JsonNode noTag = uploads.presigned(bearer, "image/png", 100);
		assertThat(UploadClient.put(noTag, UploadClient.bytes(100), Map.of("x-amz-tagging", "status=done")).statusCode())
			.isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-11#B1 허용 타입은 image/png·jpeg·webp·gif 뿐이고 확장자는 타입에서 정한다(대소문자 무시)")
	void allowedTypes() {
		String bearer = fixtures.bearer(fixtures.user());
		Map<String, String> extensions = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "image/gif",
				"gif", "IMAGE/PNG", "png", " image/jpeg ", "jpg");
		extensions.forEach((type, ext) -> {
			JsonNode body = uploads.presigned(bearer, type, 10);
			assertThat(body.get("key").asString()).as(type).endsWith("." + ext);
			assertThat(body.get("headers").properties().stream()
				.filter(e -> e.getKey().equalsIgnoreCase("content-type")).findFirst().orElseThrow().getValue().asString())
				.isEqualTo(type.strip().toLowerCase(java.util.Locale.ROOT));
		});
	}

	@Test
	@DisplayName("TASK-11#B1 그 밖의 타입은 400 attachment_type_not_allowed (svg·html·pdf·heic·파라미터 붙은 타입 포함)")
	void disallowedTypes() {
		String bearer = fixtures.bearer(fixtures.user());
		for (String type : List.of("image/svg+xml", "text/html", "application/pdf", "image/heic", "image/bmp",
				"application/octet-stream", "image/png; charset=utf-8", "image/*", "")) {
			HttpResponse<String> response = uploads.presign(bearer, type, 10);
			assertThat(response.statusCode()).as(type).isEqualTo(400);
			assertThat(error(response)).as(type).isEqualTo("attachment_type_not_allowed");
		}
		HttpResponse<String> missing = api.post("/attachments/presign").bearer(bearer).json("{\"size\":10}").send();
		assertThat(missing.statusCode()).isEqualTo(400);
		assertThat(error(missing)).isEqualTo("validation");
	}

	@Test
	@DisplayName("TASK-11#B1 크기: 5MB(5242880) 까지 허용, 넘으면 400 attachment_too_large, 0 이하·누락은 400 validation")
	void sizeLimit() {
		String bearer = fixtures.bearer(fixtures.user());
		assertThat(uploads.presign(bearer, "image/png", FIVE_MB).statusCode()).isEqualTo(200);
		assertThat(uploads.presign(bearer, "image/png", 1).statusCode()).isEqualTo(200);

		HttpResponse<String> tooLarge = uploads.presign(bearer, "image/png", FIVE_MB + 1);
		assertThat(tooLarge.statusCode()).isEqualTo(400);
		assertThat(error(tooLarge)).isEqualTo("attachment_too_large");

		for (String json : List.of("{\"contentType\":\"image/png\",\"size\":0}", "{\"contentType\":\"image/png\",\"size\":-1}",
				"{\"contentType\":\"image/png\"}")) {
			HttpResponse<String> response = api.post("/attachments/presign").bearer(bearer).json(json).send();
			assertThat(response.statusCode()).as(json).isEqualTo(400);
			assertThat(error(response)).as(json).isEqualTo("validation");
		}
		Integer rows = jdbc.queryForObject("select count(*) from attachments where size > ?", Integer.class, FIVE_MB);
		assertThat(rows).isZero();
	}

	@Test
	@DisplayName("TASK-11#1 filename 은 선택이고 키에 쓰지 않는다(경로 조작 불가)")
	void filenameIgnoredForKey() {
		User user = fixtures.user();
		HttpResponse<String> response = api.post("/attachments/presign").bearer(fixtures.bearer(user))
			.json("{\"contentType\":\"image/gif\",\"size\":5,\"filename\":\"../../etc/passwd.gif\"}").send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("key").asString()).startsWith("uploads/" + user.getId() + "/")
			.doesNotContain("..").doesNotContain("passwd");
	}

	@Test
	@DisplayName("TASK-11#1 익명은 401, 세션은 CSRF 필요, 정지 계정은 403")
	void security() {
		String json = UploadClient.presignBody("image/png", 10);
		assertThat(api.post("/attachments/presign").json(json).send().statusCode()).isIn(401, 403);
		String csrf = Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
		assertThat(api.post("/attachments/presign").csrf(csrf).json(json).send().statusCode()).isEqualTo(401);

		String session = fixtures.sessionCookie(fixtures.user());
		assertThat(api.post("/attachments/presign").cookie(session).json(json).send().statusCode()).isEqualTo(403);
		assertThat(api.post("/attachments/presign").cookie(session).csrf(csrf).json(json).send().statusCode())
			.isEqualTo(200);

		HttpResponse<String> suspended = uploads.presign(fixtures.bearer(fixtures.suspended()), "image/png", 10);
		assertThat(suspended.statusCode()).isEqualTo(403);
		assertThat(error(suspended)).isEqualTo("suspended");
	}

}
