package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;

import tools.jackson.databind.JsonNode;

/**
 * TASK-11#3 로컬(MinIO) 전체 흐름: presign → 브라우저처럼 presigned URL 로 직접 PUT → confirm → 글에 연결 → 상세. MinIO 는
 * docker-compose 와 같은 이미지이고, CORS 는 MinIO 전역 설정(MINIO_API_CORS_ALLOW_ORIGIN)으로 프론트 개발 서버 origin 만 허용한다
 * (MinIO 는 버킷 CORS API 를 구현하지 않는다).
 */
@IntegrationTest
class AttachmentFlowTest {

	static final String FRONTEND_ORIGIN = "http://localhost:3000";

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
	}

	private static HttpResponse<Void> preflight(String url, String origin) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
			.header("Origin", origin)
			.header("Access-Control-Request-Method", "PUT")
			.header("Access-Control-Request-Headers", "content-type,x-amz-tagging")
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.discarding());
	}

	@Test
	@DisplayName("TASK-11#3 MinIO: presign → presigned PUT → confirm → 글 작성 attachmentIds → 상세 attachments")
	void fullFlow() {
		String bearer = fixtures.bearer(fixtures.user());
		JsonNode presigned = uploads.presigned(bearer, "image/png", 4096);
		HttpResponse<String> put = UploadClient.put(presigned, UploadClient.bytes(4096));
		assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
		long id = presigned.get("attachmentId").asLong();
		assertThat(uploads.confirm(bearer, id).statusCode()).isEqualTo(200);

		HttpResponse<String> created = api.post("/posts").bearer(bearer)
			.json("{\"title\":\"사진 글\",\"content\":\"본문\",\"attachmentIds\":[" + id + "]}").send();
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		long postId = AuthClient.json(created).get("id").asLong();

		JsonNode detail = AuthClient.json(api.get("/posts/" + postId).send());
		assertThat(detail.get("attachments").valueStream().map(a -> a.get("id").asLong()).toList()).isEqualTo(List.of(id));
	}

	@Test
	@DisplayName("TASK-11#3 브라우저 preflight: 프론트 origin 은 PUT + content-type·x-amz-tagging 헤더 허용, 다른 origin 은 허용 안 함")
	void corsPreflight() throws Exception {
		String url = uploads.presigned(fixtures.bearer(fixtures.user()), "image/png", 10).get("uploadUrl").asString();

		HttpResponse<Void> allowed = preflight(url, FRONTEND_ORIGIN);
		assertThat(allowed.statusCode()).isBetween(200, 204);
		assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue(FRONTEND_ORIGIN);
		assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PUT");
		assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
			.contains("content-type").contains("x-amz-tagging");

		HttpResponse<Void> denied = preflight(url, "http://evil.test");
		assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
	}

}
