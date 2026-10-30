package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import com.sun.net.httpserver.HttpServer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.model.Tag;
import tools.jackson.databind.JsonNode;

/**
 * TASK-26#1 BE-32 confirm 중 S3 오류(403, 5xx, SdkClientException)는 500 이 아니라 503 {@code storage_unavailable}(TASK-20 오류 계약)이고
 * WARN 로그 한 줄에 작업·키·HTTP 상태·예외 클래스가 남는다. 자격 증명·서명·예외 메시지 원문은 남지 않는다. 첨부는 PENDING 그대로라 S3 가
 * 돌아오면 다시 confirm 할 수 있다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class AttachmentStorageFailureTest {

	static final String BUCKET = TestcontainersConfiguration.UPLOADS_BUCKET;

	static final String WRONG_SECRET = "wrong-secret-0123456789abcdef";

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

	UploadClient uploads;

	S3Client s3;

	final List<AutoCloseable> closeables = new ArrayList<>();

	@BeforeEach
	void setUp() {
		uploads = new UploadClient(new Api(port));
		s3 = UploadClient.s3(minio);
	}

	@AfterEach
	void tearDown() throws Exception {
		probe.reset();
		s3.close();
		for (AutoCloseable closeable : closeables) {
			closeable.close();
		}
	}

	/** {@code endpoint} 를 쓰는 앱과 같은 종류의 S3 저장소. */
	private S3UploadStorage storage(String endpoint, String accessKey, String secretKey) {
		StaticCredentialsProvider credentials = StaticCredentialsProvider
			.create(AwsBasicCredentials.create(accessKey, secretKey));
		S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
		S3Client client = S3Client.builder()
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create(endpoint))
			.serviceConfiguration(pathStyle)
			.credentialsProvider(credentials)
			.httpClient(UrlConnectionHttpClient.create())
			.build();
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create(endpoint))
			.serviceConfiguration(pathStyle)
			.credentialsProvider(credentials)
			.build();
		closeables.add(client);
		closeables.add(presigner);
		return new S3UploadStorage(client, presigner, BUCKET);
	}

	/** 모든 요청에 {@code status} 로 답하는 로컬 HTTP 서버(본문 없음). */
	private String serverAnswering(int status) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		closeables.add(() -> server.stop(0));
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** 업로드까지 마친 PENDING 첨부. */
	private JsonNode uploaded(String bearer) {
		JsonNode presigned = uploads.presigned(bearer, "image/png", 10);
		assertThat(UploadClient.put(presigned, UploadClient.bytes(10)).statusCode()).isEqualTo(200);
		return presigned;
	}

	private String status(long id) {
		return jdbc.queryForObject("select status from attachments where id = ?", String.class, id);
	}

	private static void assertStorageUnavailable(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("status").asInt()).isEqualTo(503);
		assertThat(body.get("error").asString()).isEqualTo("storage_unavailable");
		assertThat(body.get("message").asString()).isNotBlank().doesNotContain("Exception");
	}

	private static List<String> storageLogLines(CapturedOutput output) {
		return output.getAll().lines().filter(line -> line.contains("첨부 저장소 오류")).toList();
	}

	@Test
	@DisplayName("TASK-26#1 confirm 의 HeadObject 가 403(자격 증명 오류)이면 503 storage_unavailable + WARN(op·key·status=403), 비밀값 없음, 첨부는 PENDING")
	void headForbidden(CapturedOutput output) {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		probe.route(Op.HEAD, storage(minio.getS3URL(), minio.getUserName(), WRONG_SECRET));

		assertStorageUnavailable(uploads.confirm(bearer, id));

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(storageLogLines(output)).singleElement().satisfies(line -> assertThat(line).contains("WARN")
			.contains("op=head")
			.contains("key=" + key)
			.contains("status=403")
			.contains("S3Exception"));
		assertThat(output.getAll()).doesNotContain(WRONG_SECRET).doesNotContain(minio.getPassword())
			.doesNotContain("X-Amz-Signature");

		probe.reset();
		assertThat(uploads.confirm(bearer, id).statusCode()).as("S3 가 돌아오면 다시 confirm 된다").isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-26#1 confirm 의 HeadObject 가 5xx 이면 503 storage_unavailable + WARN(status=500)")
	void headServerError(CapturedOutput output) throws IOException {
		String bearer = fixtures.bearer(fixtures.user());
		long id = uploaded(bearer).get("attachmentId").asLong();
		probe.route(Op.HEAD, storage(serverAnswering(500), "test", "test"));

		assertStorageUnavailable(uploads.confirm(bearer, id));

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(storageLogLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("op=head").contains("status=500"));
	}

	@Test
	@DisplayName("TASK-26#1 confirm 중 S3 에 닿지 못하면(SdkClientException) 503 storage_unavailable + WARN(예외 클래스)")
	void headUnreachable(CapturedOutput output) {
		String bearer = fixtures.bearer(fixtures.user());
		long id = uploaded(bearer).get("attachmentId").asLong();
		probe.route(Op.HEAD, storage("http://127.0.0.1:1", "test", "test"));

		assertStorageUnavailable(uploads.confirm(bearer, id));

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(storageLogLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("op=head").contains("SdkClientException"));
	}

	@Test
	@DisplayName("TASK-26#1 confirm 의 태그 제거(DeleteObjectTagging)가 실패하면 503 storage_unavailable, 첨부는 PENDING 이고 pending 태그도 남는다(lifecycle 대상)")
	void clearTagFails(CapturedOutput output) throws IOException {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploaded(bearer);
		long id = presigned.get("attachmentId").asLong();
		String key = presigned.get("key").asString();
		probe.route(Op.CLEAR_TAG, storage(serverAnswering(503), "test", "test"));

		assertStorageUnavailable(uploads.confirm(bearer, id));

		assertThat(status(id)).isEqualTo("PENDING");
		assertThat(s3.getObjectTagging(b -> b.bucket(BUCKET).key(key)).tagSet())
			.containsExactly(Tag.builder().key("status").value("pending").build());
		assertThat(storageLogLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("op=clear-pending-tag").contains("status=503"));
	}

}
