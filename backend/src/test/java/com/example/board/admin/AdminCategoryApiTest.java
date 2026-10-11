package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/** TASK-12#3 관리자 카테고리 생성·수정·삭제. */
@IntegrationTest
class AdminCategoryApiTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	User admin;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		admin = fixtures.admin();
		adminBearer = fixtures.bearer(admin);
	}

	/** 소문자·숫자·하이픈 2~30자에 맞는 새 slug. */
	private static String slug() {
		return BoardFixtures.unique("t-");
	}

	private HttpResponse<String> create(String json) {
		return api.post("/admin/categories").bearer(adminBearer).json(json).send();
	}

	private JsonNode created(String slug, String name, Integer sortOrder, Boolean adminOnly) {
		StringBuilder json = new StringBuilder("{\"slug\":" + AuthClient.quote(slug) + ",\"name\":"
				+ AuthClient.quote(name));
		if (sortOrder != null) {
			json.append(",\"sortOrder\":").append(sortOrder);
		}
		if (adminOnly != null) {
			json.append(",\"adminOnly\":").append(adminOnly);
		}
		HttpResponse<String> response = create(json.append("}").toString());
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return AuthClient.json(response);
	}

	private HttpResponse<String> patch(long id, String json) {
		return api.patch("/admin/categories/" + id).bearer(adminBearer).json(json).send();
	}

	private JsonNode publicCategory(String slug) {
		for (JsonNode category : AuthClient.json(api.get("/categories").send())) {
			if (category.get("slug").asString().equals(slug)) {
				return category;
			}
		}
		return null;
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	private List<Map<String, Object>> auditRows(long categoryId) {
		return jdbc.queryForList(
				"select admin_id, action from admin_actions where target_type = 'CATEGORY' and target_id = ? order by id",
				categoryId);
	}

	@Test
	@DisplayName("TASK-12#3 생성: 201 {id, slug, name, sortOrder, adminOnly}, 공개 /categories 에 sort_order 순으로 보인다")
	void createCategory() {
		String slug = slug();
		JsonNode category = created(slug, "새 게시판", 5, true);
		assertThat(category.get("id").asLong()).isPositive();
		assertThat(category.get("slug").asString()).isEqualTo(slug);
		assertThat(category.get("name").asString()).isEqualTo("새 게시판");
		assertThat(category.get("sortOrder").asInt()).isEqualTo(5);
		assertThat(category.get("adminOnly").asBoolean()).isTrue();

		JsonNode listed = publicCategory(slug);
		assertThat(listed).isNotNull();
		assertThat(listed.get("adminOnly").asBoolean()).isTrue();

		List<String> slugs = new ArrayList<>();
		AuthClient.json(api.get("/categories").send()).forEach(c -> slugs.add(c.get("slug").asString()));
		assertThat(slugs).containsSubsequence("notice", slug, "free");

		// adminOnly 카테고리에 USER 는 쓸 수 없다(TASK-8 규칙이 새 카테고리에도 적용)
		HttpResponse<String> write = api.post("/posts")
			.bearer(fixtures.bearer(fixtures.user()))
			.json("{\"title\":\"t\",\"content\":\"c\",\"categorySlug\":" + AuthClient.quote(slug) + "}")
			.send();
		assertThat(write.statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-12#3 생성 기본값: sortOrder 생략 시 맨 뒤(현재 최대 + 1), adminOnly 생략 시 false")
	void createDefaults() {
		Integer max = jdbc.queryForObject("select max(sort_order) from categories", Integer.class);
		JsonNode category = created(slug(), "기본값", null, null);
		assertThat(category.get("sortOrder").asInt()).isEqualTo(max + 1);
		assertThat(category.get("adminOnly").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("TASK-12#3 검증: slug 는 ^[a-z0-9-]{2,30}$, name 은 1~50자, 중복 slug 409 slug_taken")
	void validation() {
		for (String bad : List.of("a", "Upper", "has space", "under_score", "x".repeat(31), "한글")) {
			HttpResponse<String> response = create("{\"slug\":" + AuthClient.quote(bad) + ",\"name\":\"n\"}");
			assertThat(response.statusCode()).as(bad).isEqualTo(400);
		}
		assertThat(create("{\"name\":\"n\"}").statusCode()).isEqualTo(400);
		assertThat(create("{\"slug\":" + AuthClient.quote(slug()) + ",\"name\":\" \"}").statusCode()).isEqualTo(400);
		assertThat(create("{\"slug\":" + AuthClient.quote(slug()) + ",\"name\":" + AuthClient.quote("n".repeat(51))
				+ "}")
			.statusCode()).isEqualTo(400);

		HttpResponse<String> duplicate = create("{\"slug\":\"free\",\"name\":\"중복\"}");
		assertThat(duplicate.statusCode()).isEqualTo(409);
		assertThat(error(duplicate)).isEqualTo("slug_taken");
	}

	@Test
	@DisplayName("TASK-12#3 수정: 부분 수정(보낸 필드만), 같은 slug 유지는 허용, 남의 slug 로 바꾸면 409, 없는 id 404")
	void updateCategory() {
		String slug = slug();
		long id = created(slug, "원래 이름", 50, false).get("id").asLong();

		HttpResponse<String> renamed = patch(id, "{\"name\":\"바뀐 이름\",\"adminOnly\":true}");
		assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(renamed);
		assertThat(body.get("slug").asString()).isEqualTo(slug);
		assertThat(body.get("name").asString()).isEqualTo("바뀐 이름");
		assertThat(body.get("sortOrder").asInt()).isEqualTo(50);
		assertThat(body.get("adminOnly").asBoolean()).isTrue();

		String newSlug = slug();
		JsonNode moved = AuthClient.json(patch(id, "{\"slug\":" + AuthClient.quote(newSlug) + ",\"sortOrder\":7}"));
		assertThat(moved.get("slug").asString()).isEqualTo(newSlug);
		assertThat(moved.get("sortOrder").asInt()).isEqualTo(7);
		assertThat(publicCategory(newSlug).get("name").asString()).isEqualTo("바뀐 이름");
		assertThat(publicCategory(slug)).isNull();

		assertThat(patch(id, "{\"slug\":" + AuthClient.quote(newSlug) + "}").statusCode()).isEqualTo(200);
		HttpResponse<String> taken = patch(id, "{\"slug\":\"free\"}");
		assertThat(taken.statusCode()).isEqualTo(409);
		assertThat(error(taken)).isEqualTo("slug_taken");
		assertThat(patch(id, "{\"slug\":\"BAD\"}").statusCode()).isEqualTo(400);
		assertThat(patch(id, "{\"name\":\"\"}").statusCode()).isEqualTo(400);

		HttpResponse<String> missing = patch(Long.MAX_VALUE, "{\"name\":\"x\"}");
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(error(missing)).isEqualTo("category_not_found");
	}

	@Test
	@DisplayName("TASK-12#3 삭제: 204, 그 카테고리의 글은 남고 카테고리 없음(FK SET NULL)이 된다. 없는 id 404")
	void deleteCategory() {
		String slug = slug();
		long id = created(slug, "지울 게시판", 60, false).get("id").asLong();
		User author = fixtures.user();
		long post = board.post(author.getId(), slug, "남을 글");

		HttpResponse<String> deleted = api.delete("/admin/categories/" + id).bearer(adminBearer).send();
		assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
		assertThat(publicCategory(slug)).isNull();

		HttpResponse<String> detail = api.get("/posts/" + post).send();
		assertThat(detail.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(detail).get("category").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select category_id from posts where id = ?", Long.class, post)).isNull();

		assertThat(api.delete("/admin/categories/" + id).bearer(adminBearer).send().statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("TASK-12#3 생성·수정·삭제는 감사 로그에 남는다")
	void audit() {
		long id = created(slug(), "감사", 70, false).get("id").asLong();
		assertThat(patch(id, "{\"name\":\"감사2\"}").statusCode()).isEqualTo(200);
		assertThat(api.delete("/admin/categories/" + id).bearer(adminBearer).send().statusCode()).isEqualTo(204);

		List<Map<String, Object>> rows = auditRows(id);
		assertThat(rows).extracting(row -> row.get("action"))
			.containsExactly("CATEGORY_CREATE", "CATEGORY_UPDATE", "CATEGORY_DELETE");
		assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("admin_id", admin.getId()));
	}

}
