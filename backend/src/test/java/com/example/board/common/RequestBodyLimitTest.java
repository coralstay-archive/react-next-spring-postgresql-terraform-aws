package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.multipart.MultipartResolver;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/** TASK-23#2 (BE-4) 요청 본문 상한 64KB. 첨부는 S3 presigned PUT 이라 API 본문으로 오지 않는다. */
@IntegrationTest
class RequestBodyLimitTest {

	static final int LIMIT = 64 * 1024;

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	ApplicationContext context;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private static String postBody(String title, String content) {
		return "{\"title\":" + AuthClient.quote(title) + ",\"content\":" + AuthClient.quote(content) + "}";
	}

	private static void assertTooLarge(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(413);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("status").asInt()).isEqualTo(413);
		assertThat(body.get("error").asString()).isEqualTo("payload_too_large");
		assertThat(body.get("message").asString()).isNotBlank();
	}

	@Test
	@DisplayName("TASK-23#2 Content-Length 가 64KB 를 넘으면 본문을 읽지 않고 413 payload_too_large JSON")
	void contentLengthOverLimit() {
		User user = fixtures.user();
		String body = postBody("큰 글", "a".repeat(LIMIT));
		assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(LIMIT);
		assertTooLarge(api.post("/posts").bearer(fixtures.bearer(user)).json(body).send());
		// 인증 전 단계(익명 로그인)도 같다
		assertTooLarge(api.post("/auth/login").json("{\"email\":\"a@b.c\",\"password\":" + AuthClient.quote("p".repeat(LIMIT)) + "}")
			.send());
	}

	@Test
	@DisplayName("TASK-23#2 길이를 모르는(chunked) 본문도 읽은 양이 64KB 를 넘으면 413 payload_too_large JSON")
	void chunkedOverLimit() throws Exception {
		User user = fixtures.user();
		byte[] body = postBody("큰 글", "a".repeat(LIMIT)).getBytes(StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/posts"))
			.header("Content-Type", "application/json")
			.header("Authorization", "Bearer " + fixtures.bearer(user))
			.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
		assertTooLarge(response);
	}

	@Test
	@DisplayName("TASK-23#2 가장 큰 정상 글(한글 제목 100자 + 본문 20000자 ≈ 60KB, UTF-8 3바이트)은 상한 안이라 201")
	void largestValidPostFits() {
		User user = fixtures.user();
		String body = "{\"title\":" + AuthClient.quote("가".repeat(100)) + ",\"content\":"
				+ AuthClient.quote("나".repeat(20000)) + ",\"tags\":[" + AuthClient.quote("다".repeat(30)) + ","
				+ AuthClient.quote("라".repeat(30)) + "," + AuthClient.quote("마".repeat(30)) + ","
				+ AuthClient.quote("바".repeat(30)) + "," + AuthClient.quote("사".repeat(30)) + "]}";
		assertThat(body.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(60_000).isLessThan(LIMIT);
		HttpResponse<String> response = api.post("/posts").bearer(fixtures.bearer(user)).json(body).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
	}

	private HttpResponse<String> chunked(String path, String contentType, byte[] body, String bearer) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
			.header("Content-Type", contentType)
			.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("TASK-23#2 길이를 모르는(chunked) form-urlencoded 본문도 64KB 를 넘으면 413 payload_too_large JSON — 컨테이너의 파라미터 파싱(CSRF 필터의 _csrf 조회 등)이 상한을 돌아가지 못한다")
	void chunkedFormOverLimit() throws Exception {
		byte[] body = ("_csrf=x&title=" + "a".repeat(LIMIT)).getBytes(StandardCharsets.US_ASCII);
		// 세션(쿠키) 요청 경로: CsrfFilter 가 헤더가 없으면 getParameter("_csrf") 로 본문을 파싱한다
		assertTooLarge(chunked("/posts", "application/x-www-form-urlencoded", body, null));
		assertTooLarge(chunked("/posts", "application/x-www-form-urlencoded; charset=UTF-8", body,
				fixtures.bearer(fixtures.user())));
	}

	@Test
	@DisplayName("TASK-23#2 multipart 는 파싱하지 않는다(멀티파트 엔드포인트 없음, MultipartResolver 없음): chunked multipart 64KB 초과도 본문을 읽지 않고 JSON 오류")
	void chunkedMultipartNotParsed() throws Exception {
		assertThat(context.getBeanNamesForType(MultipartResolver.class)).isEmpty();
		String boundary = "----board-test";
		byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\nx\r\n--" + boundary
				+ "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n" + "a".repeat(LIMIT)
				+ "\r\n--" + boundary + "--\r\n")
			.getBytes(StandardCharsets.US_ASCII);
		HttpResponse<String> response = chunked("/posts", "multipart/form-data; boundary=" + boundary, body,
				fixtures.bearer(fixtures.user()));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(415);
		JsonNode error = AuthClient.json(response);
		assertThat(error.get("error").asString()).isEqualTo("unsupported_media_type");
	}

}
