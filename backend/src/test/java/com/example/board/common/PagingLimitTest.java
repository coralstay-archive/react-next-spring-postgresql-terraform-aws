package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** TASK-23#5 (BE-30) 오프셋 상한: page × size ≤ 10,000. 넘으면 400 validation + fields.page (깊은 OFFSET 스캔 방지). */
@IntegrationTest
class PagingLimitTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	Api api;

	String adminBearer;

	long postId;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		adminBearer = fixtures.bearer(fixtures.admin());
		postId = board.post(fixtures.user().getId(), null, "페이지");
	}

	private HttpResponse<String> list(String path) {
		return path.startsWith("/admin") ? api.get(path).bearer(adminBearer).send() : api.get(path).send();
	}

	private static void assertPageError(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("error").asString()).isEqualTo("validation");
		assertThat(body.get("fields").get("page").asString()).contains("10000");
	}

	private String[] paths() {
		return new String[] { "/posts", "/posts/" + postId + "/comments", "/admin/users", "/admin/actions" };
	}

	@Test
	@DisplayName("TASK-23#5 page × size 가 10,000 을 넘으면 글·댓글·관리자 목록 모두 400 validation + fields.page")
	void overLimit() {
		for (String path : paths()) {
			assertPageError(list(path + "?page=201&size=50"));
			assertPageError(list(path + "?page=10001&size=1"));
		}
	}

	@Test
	@DisplayName("TASK-23#5 정확히 10,000 행 오프셋까지는 된다(빈 페이지 200)")
	void atLimit() {
		for (String path : paths()) {
			HttpResponse<String> response = list(path + "?page=200&size=50");
			assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-23#5 int 최대 page 도 곱셈 오버플로 없이 400")
	void hugePage() {
		for (String path : paths()) {
			assertPageError(list(path + "?page=" + Integer.MAX_VALUE + "&size=50"));
		}
	}

}
