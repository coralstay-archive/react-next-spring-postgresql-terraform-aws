package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

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

/** TASK-8#4 GET /api/categories · GET /api/tags (공개). */
@IntegrationTest
class CategoryTagApiTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private JsonNode ok(String path) {
		HttpResponse<String> response = api.get(path).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	@Test
	@DisplayName("TASK-8#4 GET /categories: 익명 공개, sort_order 순 [{id, slug, name, sortOrder, adminOnly}]")
	void categories() {
		String mine = board.category();

		JsonNode categories = ok("/categories");
		List<String> slugs = new ArrayList<>();
		categories.forEach(c -> slugs.add(c.get("slug").asString()));
		assertThat(slugs).containsSubsequence("notice", "free", mine);

		JsonNode notice = categories.get(slugs.indexOf("notice"));
		assertThat(notice.get("id").asLong()).isPositive();
		assertThat(notice.get("name").asString()).isEqualTo("공지");
		assertThat(notice.get("sortOrder").asInt()).isZero();
		assertThat(notice.get("adminOnly").asBoolean()).isTrue();
		assertThat(categories.get(slugs.indexOf("free")).get("adminOnly").asBoolean()).isFalse();
		int previous = Integer.MIN_VALUE;
		for (JsonNode category : categories) {
			assertThat(category.get("sortOrder").asInt()).isGreaterThanOrEqualTo(previous);
			previous = category.get("sortOrder").asInt();
		}
	}

	@Test
	@DisplayName("TASK-8#4 GET /tags: 보이는 글에 쓰인 태그와 글 수, 많이 쓰인 순(같으면 이름 순), ?q= 접두어(정규화)·limit")
	void tags() {
		long author = fixtures.user().getId();
		String prefix = BoardFixtures.unique("p");
		String a = prefix + "-a";
		String b = prefix + "-b";
		String c = prefix + "-c";
		String d = prefix + "-d";
		board.post(author, null, "1", a, b);
		board.post(author, null, "2", a);
		board.post(author, null, "3", a, d);
		board.hide(board.post(author, null, "숨김", b, d));
		board.delete(board.post(author, null, "삭제", c));

		JsonNode tags = ok("/tags?q=" + prefix.toUpperCase());
		assertThat(tags).hasSize(3);
		assertThat(tags.get(0).get("name").asString()).isEqualTo(a);
		assertThat(tags.get(0).get("postCount").asLong()).isEqualTo(3);
		assertThat(tags.get(1).get("name").asString()).isEqualTo(b);
		assertThat(tags.get(1).get("postCount").asLong()).as("숨김 글은 세지 않는다").isEqualTo(1);
		assertThat(tags.get(2).get("name").asString()).isEqualTo(d);
		assertThat(tags.get(2).get("postCount").asLong()).isEqualTo(1);

		assertThat(ok("/tags?q=" + prefix + "&limit=1")).hasSize(1);
		assertThat(ok("/tags?q=" + prefix + "-b")).hasSize(1);
		assertThat(ok("/tags?limit=100").size()).isLessThanOrEqualTo(100);
	}

	@Test
	@DisplayName("TASK-8#4 /tags 의 q 는 접두어 그대로 비교한다(% · _ 는 와일드카드가 아님), limit 은 1~100")
	void tagQueryEscapingAndLimit() {
		long author = fixtures.user().getId();
		String prefix = BoardFixtures.unique("e");
		board.post(author, null, "x", prefix + "%x", prefix + "ax");

		JsonNode percent = ok("/tags?q=" + prefix + "%25");
		assertThat(percent).hasSize(1);
		assertThat(percent.get(0).get("name").asString()).isEqualTo(prefix + "%x");
		assertThat(ok("/tags?q=" + prefix + "_")).isEmpty();

		for (String query : List.of("limit=0", "limit=101", "limit=x")) {
			assertThat(api.get("/tags?" + query).send().statusCode()).as(query).isEqualTo(400);
		}
	}

}
