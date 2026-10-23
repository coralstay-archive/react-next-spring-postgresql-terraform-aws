package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * TASK-27#4 BE-40 카테고리 sortOrder 는 0~10000 이다. 벗어나면 생성·수정 모두 400 {@code validation}({@code fields.sortOrder}). 생략하면
 * 맨 뒤(최대 + 1)인데 상한을 넘지 않고(10000), 범위 밖 기존 값(검증 전 데이터, int 최댓값)이 있어도 오버플로하지 않는다.
 *
 * <p>공용 DB 의 "최대 + 1" 을 쓰는 다른 테스트에 영향이 없게 큰 sortOrder 로 만든 카테고리는 테스트가 끝나면 지운다.
 */
@IntegrationTest
class AdminCategorySortOrderTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	String adminBearer;

	final List<String> slugs = new ArrayList<>();

	@BeforeEach
	void setUp() {
		api = new Api(port);
		adminBearer = fixtures.bearer(fixtures.admin());
	}

	@AfterEach
	void tearDown() {
		slugs.forEach(slug -> jdbc.update("delete from categories where slug = ?", slug));
	}

	private String slug() {
		String slug = BoardFixtures.unique("so-").toLowerCase().replaceAll("[^a-z0-9-]", "-");
		slug = slug.length() > 30 ? slug.substring(0, 30) : slug;
		slugs.add(slug);
		return slug;
	}

	private HttpResponse<String> create(String slug, String sortOrder) {
		String json = "{\"slug\":" + AuthClient.quote(slug) + ",\"name\":\"순서\""
				+ (sortOrder == null ? "" : ",\"sortOrder\":" + sortOrder) + "}";
		return api.post("/admin/categories").bearer(adminBearer).json(json).send();
	}

	private static void assertSortOrderInvalid(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("error").asString()).isEqualTo("validation");
		assertThat(body.get("fields").has("sortOrder")).as(response.body()).isTrue();
	}

	@Test
	@DisplayName("TASK-27#4 생성: sortOrder 0·10000 은 201, -1·10001·int 최댓값은 400 validation fields.sortOrder")
	void createRange() {
		assertThat(create(slug(), "0").statusCode()).isEqualTo(201);
		assertThat(create(slug(), "10000").statusCode()).isEqualTo(201);
		assertSortOrderInvalid(create(slug(), "-1"));
		assertSortOrderInvalid(create(slug(), "10001"));
		assertSortOrderInvalid(create(slug(), String.valueOf(Integer.MAX_VALUE)));
	}

	@Test
	@DisplayName("TASK-27#4 수정: 범위 밖 sortOrder 는 400 validation fields.sortOrder 이고 값은 그대로")
	void updateRange() {
		HttpResponse<String> created = create(slug(), "7");
		long id = AuthClient.json(created).get("id").asLong();

		assertSortOrderInvalid(api.patch("/admin/categories/" + id).bearer(adminBearer).json("{\"sortOrder\":-5}").send());
		assertSortOrderInvalid(
				api.patch("/admin/categories/" + id).bearer(adminBearer).json("{\"sortOrder\":10001}").send());
		assertThat(jdbc.queryForObject("select sort_order from categories where id = ?", Integer.class, id)).isEqualTo(7);
	}

	@Test
	@DisplayName("TASK-27#4 생략 시 맨 뒤(최대 + 1)는 상한 10000 을 넘지 않고, 범위 밖 기존 값(int 최댓값)이 있어도 오버플로하지 않는다")
	void nextSortOrderDoesNotOverflow() {
		assertThat(create(slug(), "10000").statusCode()).isEqualTo(201);
		HttpResponse<String> atMax = create(slug(), null);
		assertThat(atMax.statusCode()).as(atMax.body()).isEqualTo(201);
		assertThat(AuthClient.json(atMax).get("sortOrder").asInt()).isEqualTo(10000);

		String legacy = slug();
		jdbc.update("insert into categories (slug, name, sort_order) values (?, '옛 값', ?)", legacy, Integer.MAX_VALUE);
		HttpResponse<String> afterLegacy = create(slug(), null);
		assertThat(afterLegacy.statusCode()).as(afterLegacy.body()).isEqualTo(201);
		assertThat(AuthClient.json(afterLegacy).get("sortOrder").asInt()).isEqualTo(10000);
	}

}
