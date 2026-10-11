package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

/** TASK-8#3 글 작성·수정·삭제 + TASK-8#B1 작성자|ADMIN + TASK-8#B3 태그 정규화·재사용·최대 5개. */
@IntegrationTest
class PostWriteTest {

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

	private String csrf() {
		return Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
	}

	private static String body(String title, String content, String categorySlug, String... tags) {
		StringBuilder json = new StringBuilder("{\"title\":").append(AuthClient.quote(title))
			.append(",\"content\":").append(AuthClient.quote(content));
		if (categorySlug != null) {
			json.append(",\"categorySlug\":").append(AuthClient.quote(categorySlug));
		}
		if (tags.length > 0) {
			json.append(",\"tags\":[")
				.append(String.join(",", List.of(tags).stream().map(AuthClient::quote).toList()))
				.append("]");
		}
		return json.append("}").toString();
	}

	private HttpResponse<String> create(String bearer, String json) {
		return api.post("/posts").bearer(bearer).json(json).send();
	}

	/** 글을 만들고 id. */
	private long created(User author, String... tags) {
		HttpResponse<String> response = create(fixtures.bearer(author), body("제목", "본문", "free", tags));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return AuthClient.json(response).get("id").asLong();
	}

	private static List<String> tags(JsonNode post) {
		return post.get("tags").valueStream().map(JsonNode::asString).toList();
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	// ---- TASK-8#3 작성 ----

	@Test
	@DisplayName("TASK-8#3 POST /posts (Bearer): 201 + Location + 상세 본문(editable=true)")
	void createWithBearer() {
		User author = fixtures.user();
		HttpResponse<String> response = create(fixtures.bearer(author), body("첫 글", "내용", "free", "Java"));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		JsonNode post = AuthClient.json(response);
		long id = post.get("id").asLong();
		assertThat(response.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/api/posts/" + id));
		assertThat(post.get("title").asString()).isEqualTo("첫 글");
		assertThat(post.get("content").asString()).isEqualTo("내용");
		assertThat(post.get("author").get("id").asLong()).isEqualTo(author.getId());
		assertThat(post.get("category").get("slug").asString()).isEqualTo("free");
		assertThat(tags(post)).containsExactly("java");
		assertThat(post.get("editable").asBoolean()).isTrue();

		JsonNode read = AuthClient.json(api.get("/posts/" + id).send());
		assertThat(read.get("title").asString()).isEqualTo("첫 글");
	}

	@Test
	@DisplayName("TASK-8#3 카테고리·태그 없이도 쓸 수 있다(category=null, tags=[])")
	void createWithoutCategory() {
		HttpResponse<String> response = create(fixtures.bearer(fixtures.user()), "{\"title\":\"t\",\"content\":\"c\"}");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		JsonNode post = AuthClient.json(response);
		assertThat(post.get("category").isNull()).isTrue();
		assertThat(tags(post)).isEmpty();
	}

	@Test
	@DisplayName("TASK-8#3 세션 쓰기는 CSRF 필요(없으면 403), 익명은 401")
	void sessionNeedsCsrfAnonymousUnauthorized() {
		User author = fixtures.user();
		String session = fixtures.sessionCookie(author);
		String json = body("세션 글", "내용", "free");

		assertThat(api.post("/posts").cookie(session).json(json).send().statusCode()).isEqualTo(403);
		assertThat(api.post("/posts").cookie(session).csrf(csrf()).json(json).send().statusCode()).isEqualTo(201);
		assertThat(api.post("/posts").csrf(csrf()).json(json).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-8#3 정지 계정·제한(step-up) 토큰의 쓰기는 중앙 필터가 403 으로 막는다")
	void centralFiltersApply() {
		String json = body("t", "c", "free");
		HttpResponse<String> suspended = create(fixtures.bearer(fixtures.suspended()), json);
		assertThat(suspended.statusCode()).isEqualTo(403);
		assertThat(error(suspended)).isEqualTo("suspended");

		User author = fixtures.user();
		long id = created(author);
		HttpResponse<String> stepUp = api.patch("/posts/" + id).bearer(fixtures.stepUpBearer(author))
			.json("{\"title\":\"x\"}").send();
		assertThat(stepUp.statusCode()).isEqualTo(403);
		assertThat(error(stepUp)).isEqualTo("step_up_required");
	}

	@Test
	@DisplayName("TASK-8#3 검증: 제목 1~100자(공백만 불가)·본문 1~20000자 → 아니면 400 validation")
	void validation() {
		String bearer = fixtures.bearer(fixtures.user());
		List<String> invalid = List.of(body("", "c", null), body("   ", "c", null), body("a".repeat(101), "c", null),
				body("t", "", null), body("t", " \n ", null), body("t", "a".repeat(20001), null),
				"{\"content\":\"c\"}", "{\"title\":\"t\"}");
		for (String json : invalid) {
			HttpResponse<String> response = create(bearer, json);
			assertThat(response.statusCode()).as(json.length() > 80 ? json.substring(0, 80) : json).isEqualTo(400);
			assertThat(error(response)).isEqualTo("validation");
		}
		assertThat(create(bearer, body("a".repeat(100), "a".repeat(20000), null)).statusCode()).isEqualTo(201);
		assertThat(create(bearer, "not json").statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-8#3 없는 카테고리는 400 unknown_category")
	void unknownCategory() {
		HttpResponse<String> response = create(fixtures.bearer(fixtures.user()), body("t", "c", "no-such-category"));
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(error(response)).isEqualTo("unknown_category");
	}

	@Test
	@DisplayName("TASK-8#3 ADMIN 전용 카테고리(notice): USER 는 쓰기·옮기기 403 category_admin_only, ADMIN 은 가능")
	void adminOnlyCategory() {
		User user = fixtures.user();
		HttpResponse<String> denied = create(fixtures.bearer(user), body("공지?", "c", "notice"));
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(error(denied)).isEqualTo("category_admin_only");

		long own = created(user);
		HttpResponse<String> move = api.patch("/posts/" + own).bearer(fixtures.bearer(user))
			.json("{\"categorySlug\":\"notice\"}").send();
		assertThat(move.statusCode()).isEqualTo(403);
		assertThat(error(move)).isEqualTo("category_admin_only");

		assertThat(create(fixtures.bearer(fixtures.admin()), body("공지", "c", "notice")).statusCode()).isEqualTo(201);
	}

	// ---- TASK-8#3 수정·삭제 ----

	@Test
	@DisplayName("TASK-8#3 PATCH 는 보낸 필드만 바꾼다(updatedAt 갱신). tags=[] 는 비우고 categorySlug=\"\" 는 카테고리를 뺀다")
	void partialUpdate() {
		User author = fixtures.user();
		String bearer = fixtures.bearer(author);
		long id = created(author, "one", "two");
		jdbc.update("update posts set updated_at = ? where id = ?", java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), id);

		HttpResponse<String> titleOnly = api.patch("/posts/" + id).bearer(bearer).json("{\"title\":\"새 제목\"}").send();
		assertThat(titleOnly.statusCode()).as(titleOnly.body()).isEqualTo(200);
		JsonNode updated = AuthClient.json(titleOnly);
		assertThat(updated.get("title").asString()).isEqualTo("새 제목");
		assertThat(updated.get("content").asString()).isEqualTo("본문");
		assertThat(updated.get("category").get("slug").asString()).isEqualTo("free");
		assertThat(tags(updated)).containsExactly("one", "two");
		assertThat(Instant.parse(updated.get("updatedAt").asString())).isAfter(Instant.parse("2020-01-01T00:00:00Z"));

		JsonNode retagged = AuthClient.json(api.patch("/posts/" + id).bearer(bearer)
			.json("{\"content\":\"새 본문\",\"tags\":[\"Three\"]}").send());
		assertThat(retagged.get("content").asString()).isEqualTo("새 본문");
		assertThat(tags(retagged)).containsExactly("three");

		JsonNode cleared = AuthClient.json(api.patch("/posts/" + id).bearer(bearer)
			.json("{\"tags\":[],\"categorySlug\":\"\"}").send());
		assertThat(tags(cleared)).isEmpty();
		assertThat(cleared.get("category").isNull()).isTrue();

		HttpResponse<String> blankTitle = api.patch("/posts/" + id).bearer(bearer).json("{\"title\":\" \"}").send();
		assertThat(blankTitle.statusCode()).isEqualTo(400);
		HttpResponse<String> unknown = api.patch("/posts/" + id).bearer(bearer).json("{\"categorySlug\":\"nope\"}").send();
		assertThat(unknown.statusCode()).isEqualTo(400);
		assertThat(error(unknown)).isEqualTo("unknown_category");
	}

	@Test
	@DisplayName("TASK-8#3 DELETE 는 soft delete: 204 후 상세 404·목록 제외, DB 행은 deleted_at 과 함께 남는다")
	void softDelete() {
		User author = fixtures.user();
		String slug = board.category();
		HttpResponse<String> response = create(fixtures.bearer(author), body("지울 글", "c", slug));
		long id = AuthClient.json(response).get("id").asLong();

		assertThat(api.delete("/posts/" + id).bearer(fixtures.bearer(author)).send().statusCode()).isEqualTo(204);

		assertThat(api.get("/posts/" + id).send().statusCode()).isEqualTo(404);
		assertThat(AuthClient.json(api.get("/posts?category=" + slug).send()).get("totalElements").asLong()).isZero();
		assertThat(jdbc.queryForObject("select deleted_at is not null from posts where id = ?", Boolean.class, id))
			.isTrue();
		assertThat(api.delete("/posts/" + id).bearer(fixtures.bearer(author)).send().statusCode()).isEqualTo(404);
		assertThat(api.patch("/posts/" + id).bearer(fixtures.bearer(fixtures.admin())).json("{\"title\":\"x\"}").send()
			.statusCode()).as("삭제된 글은 ADMIN 도 수정할 수 없다").isEqualTo(404);
	}

	// ---- TASK-8#B1 작성자 | ADMIN ----

	@Test
	@DisplayName("TASK-8#B1 남의 글 수정·삭제는 403 forbidden, 글은 그대로")
	void othersForbidden() {
		User author = fixtures.user();
		long id = created(author);
		String other = fixtures.bearer(fixtures.user());

		HttpResponse<String> patch = api.patch("/posts/" + id).bearer(other).json("{\"title\":\"탈취\"}").send();
		assertThat(patch.statusCode()).isEqualTo(403);
		assertThat(error(patch)).isEqualTo("forbidden");
		assertThat(api.delete("/posts/" + id).bearer(other).send().statusCode()).isEqualTo(403);
		HttpResponse<String> sessionPatch = api.patch("/posts/" + id).cookie(fixtures.sessionCookie(fixtures.user()))
			.csrf(csrf()).json("{\"title\":\"탈취\"}").send();
		assertThat(sessionPatch.statusCode()).isEqualTo(403);

		assertThat(AuthClient.json(api.get("/posts/" + id).send()).get("title").asString()).isEqualTo("제목");
	}

	@Test
	@DisplayName("TASK-8#B1 ADMIN 은 남의 글을 수정(200)·삭제(204)할 수 있다")
	void adminMayModify() {
		long id = created(fixtures.user());
		String admin = fixtures.bearer(fixtures.admin());

		HttpResponse<String> patch = api.patch("/posts/" + id).bearer(admin).json("{\"title\":\"관리자 수정\"}").send();
		assertThat(patch.statusCode()).as(patch.body()).isEqualTo(200);
		assertThat(AuthClient.json(patch).get("title").asString()).isEqualTo("관리자 수정");
		assertThat(api.delete("/posts/" + id).bearer(admin).send().statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-8#B1 없는 글의 수정·삭제는 404 post_not_found (권한 판정보다 먼저)")
	void missingIsNotFound() {
		String user = fixtures.bearer(fixtures.user());
		HttpResponse<String> patch = api.patch("/posts/999999999").bearer(user).json("{\"title\":\"x\"}").send();
		assertThat(patch.statusCode()).isEqualTo(404);
		assertThat(error(patch)).isEqualTo("post_not_found");
		assertThat(api.delete("/posts/999999999").bearer(user).send().statusCode()).isEqualTo(404);
	}

	@Test
	@DisplayName("TASK-8#B1 숨김 글: 작성자에게는 없는 글(404), ADMIN 은 수정 가능")
	void hiddenPost() {
		User author = fixtures.user();
		long id = created(author);
		board.hide(id);

		assertThat(api.patch("/posts/" + id).bearer(fixtures.bearer(author)).json("{\"title\":\"x\"}").send()
			.statusCode()).isEqualTo(404);
		assertThat(api.delete("/posts/" + id).bearer(fixtures.bearer(author)).send().statusCode()).isEqualTo(404);
		assertThat(api.patch("/posts/" + id).bearer(fixtures.bearer(fixtures.admin())).json("{\"title\":\"x\"}").send()
			.statusCode()).isEqualTo(200);
	}

	// ---- TASK-8#B3 태그 ----

	@Test
	@DisplayName("TASK-8#B3 태그는 정규화되고 한 글 안의 중복은 하나로 합친다")
	void tagsNormalizedAndDeduped() {
		HttpResponse<String> response = create(fixtures.bearer(fixtures.user()),
				body("t", "c", null, "  Spring   Boot ", "spring boot", "JAVA", "java"));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		assertThat(tags(AuthClient.json(response))).containsExactly("java", "spring boot");
	}

	@Test
	@DisplayName("TASK-8#B3 같은 이름의 태그는 새로 만들지 않고 재사용한다")
	void tagsReused() {
		String name = BoardFixtures.unique("r");
		created(fixtures.user(), name);
		created(fixtures.user(), name.toUpperCase());

		assertThat(jdbc.queryForObject("select count(*) from tags where name = ?", Integer.class, name)).isOne();
		assertThat(jdbc.queryForObject(
				"select count(*) from post_tags pt join tags t on t.id = pt.tag_id where t.name = ?", Integer.class,
				name)).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-8#B3 글당 태그는 (중복 제거 후) 최대 5개: 6개면 400 too_many_tags, 빈 태그·31자 태그는 400 invalid_tag")
	void tagLimits() {
		String bearer = fixtures.bearer(fixtures.user());
		assertThat(create(bearer, body("t", "c", null, "a", "b", "c", "d", "e", "A", " e ")).statusCode())
			.as("중복을 빼면 5개").isEqualTo(201);

		HttpResponse<String> six = create(bearer, body("t", "c", null, "a", "b", "c", "d", "e", "f"));
		assertThat(six.statusCode()).isEqualTo(400);
		assertThat(error(six)).isEqualTo("too_many_tags");

		for (String bad : List.of("   ", "x".repeat(31))) {
			HttpResponse<String> response = create(bearer, body("t", "c", null, bad));
			assertThat(response.statusCode()).isEqualTo(400);
			assertThat(error(response)).isEqualTo("invalid_tag");
		}
		HttpResponse<String> nullTag = create(bearer, "{\"title\":\"t\",\"content\":\"c\",\"tags\":[null]}");
		assertThat(nullTag.statusCode()).isEqualTo(400);

		long id = created(fixtures.user());
		HttpResponse<String> patch = api.patch("/posts/" + id).bearer(fixtures.bearer(fixtures.admin()))
			.json("{\"tags\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}").send();
		assertThat(patch.statusCode()).isEqualTo(400);
		assertThat(error(patch)).isEqualTo("too_many_tags");
	}

	@Test
	@DisplayName("TASK-8#B3 새 태그를 동시에 여러 글이 써도 모두 성공하고 태그 행은 하나 (ON CONFLICT DO NOTHING upsert)")
	void concurrentTagUpsert() throws Exception {
		String name = BoardFixtures.unique("z");
		List<String> bearers = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			bearers.add(fixtures.bearer(fixtures.user()));
		}
		ExecutorService pool = Executors.newFixedThreadPool(bearers.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (String bearer : bearers) {
				Callable<Integer> call = () -> {
					start.await();
					return create(bearer, body("동시", "c", null, name, "other-" + name)).statusCode();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(201);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from tags where name = ?", Integer.class, name)).isOne();
		assertThat(jdbc.queryForObject(
				"select count(*) from post_tags pt join tags t on t.id = pt.tag_id where t.name = ?", Integer.class,
				name)).isEqualTo(8);
	}

}
