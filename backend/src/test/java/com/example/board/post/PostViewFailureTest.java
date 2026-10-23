package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.FailingInsert;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/**
 * TASK-27#2 BE-28 조회수 집계가 DB 오류로 실패해도 이미 성공한 상세 읽기는 200 으로 돌려준다. 집계는 로그(WARN)만 남기고 세지 않는다. 실패는
 * 이 사용자의 post_views INSERT 만 막는 임시 트리거로 결정적으로 만든다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PostViewFailureTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private long views(long postId) {
		return jdbc.queryForObject("select view_count from posts where id = ?", Long.class, postId);
	}

	@Test
	@DisplayName("TASK-27#2 조회수 집계(post_views INSERT)가 실패하면 상세는 200(viewCount 는 올리지 않은 값), WARN 로그에 글 id·예외 종류만")
	void viewCountFailureKeepsDetail(CapturedOutput output) {
		User reader = fixtures.user();
		long postId = board.post(fixtures.user().getId(), null, "집계 실패");

		HttpResponse<String> response;
		try (FailingInsert failing = FailingInsert.on(jdbc, "post_views", "viewer_key", "u:" + reader.getId())) {
			response = api.get("/posts/" + postId).bearer(fixtures.bearer(reader)).send();
		}

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("id").asLong()).isEqualTo(postId);
		assertThat(AuthClient.json(response).get("viewCount").asLong()).isZero();
		assertThat(views(postId)).isZero();
		assertThat(output.getOut()).contains("조회수 집계 실패").contains("post=" + postId)
			.doesNotContain("u:" + reader.getId());

		HttpResponse<String> again = api.get("/posts/" + postId).bearer(fixtures.bearer(reader)).send();
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(views(postId)).as("장애가 지나면 다시 센다").isEqualTo(1);
	}

}
