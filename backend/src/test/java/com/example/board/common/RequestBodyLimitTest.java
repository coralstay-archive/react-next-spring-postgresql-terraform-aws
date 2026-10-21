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

}
