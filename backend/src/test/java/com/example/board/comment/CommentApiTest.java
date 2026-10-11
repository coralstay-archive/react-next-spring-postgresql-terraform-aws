package com.example.board.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

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

/**
 * TASK-9#1 댓글 목록·작성·삭제(작성자|ADMIN) + TASK-9#B3 삭제(·숨김)된 글에는 댓글을 쓸 수 없다.
 *
 * <p>삭제된 댓글은 목록에서 빼지 않고 자리표시({@code deleted=true}, 본문·작성자 null)로 남긴다(README).
 */
@IntegrationTest
class CommentApiTest {

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

	private static String body(String content) {
		return "{\"content\":" + AuthClient.quote(content) + "}";
	}

	private HttpResponse<String> write(User user, long postId, String content) {
		return api.post("/posts/" + postId + "/comments").bearer(fixtures.bearer(user)).json(body(content)).send();
	}

	private long written(User user, long postId, String content) {
		HttpResponse<String> response = write(user, postId, content);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return AuthClient.json(response).get("id").asLong();
	}

	private JsonNode list(long postId, Api.Request request) {
		HttpResponse<String> response = request.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private JsonNode list(long postId) {
		return list(postId, api.get("/posts/" + postId + "/comments"));
	}

	private static List<Long> ids(JsonNode page) {
		return page.get("items").valueStream().map(item -> item.get("id").asLong()).toList();
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	// ---- 작성 ----

	@Test
	@DisplayName("TASK-9#1 POST /posts/{id}/comments (Bearer): 201 + Location + {id, postId, content, author, createdAt, deleted=false, editable=true}")
	void create() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "댓글 대상");
		User commenter = fixtures.user();

		HttpResponse<String> response = write(commenter, postId, "첫 댓글");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		JsonNode comment = AuthClient.json(response);
		long id = comment.get("id").asLong();
		assertThat(response.headers().firstValue("Location"))
			.hasValueSatisfying(l -> assertThat(l).endsWith("/api/comments/" + id));
		assertThat(comment.get("postId").asLong()).isEqualTo(postId);
		assertThat(comment.get("content").asString()).isEqualTo("첫 댓글");
		assertThat(comment.get("author").get("id").asLong()).isEqualTo(commenter.getId());
		assertThat(comment.get("author").get("nickname").asString()).isEqualTo("tester");
		assertThat(comment.get("createdAt").isNull()).isFalse();
		assertThat(comment.get("deleted").asBoolean()).isFalse();
		assertThat(comment.get("editable").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("TASK-9#1 세션 작성은 CSRF 필요(없으면 403), 익명은 401")
	void sessionNeedsCsrfAnonymousUnauthorized() {
		User user = fixtures.user();
		long postId = board.post(user.getId(), null, "세션 댓글");
		String session = fixtures.sessionCookie(user);
		String path = "/posts/" + postId + "/comments";

		assertThat(api.post(path).cookie(session).json(body("x")).send().statusCode()).isEqualTo(403);
		assertThat(api.post(path).cookie(session).csrf(csrf()).json(body("x")).send().statusCode()).isEqualTo(201);
		assertThat(api.post(path).csrf(csrf()).json(body("x")).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-9#1 본문은 1~2000자, 공백만은 안 된다(400 validation)")
	void validation() {
		User user = fixtures.user();
		long postId = board.post(user.getId(), null, "검증");

		for (String bad : new String[] { "", "   \n ", "가".repeat(2001) }) {
			HttpResponse<String> response = write(user, postId, bad);
			assertThat(response.statusCode()).as(bad).isEqualTo(400);
			assertThat(error(response)).isEqualTo("validation");
		}
		HttpResponse<String> missing = api.post("/posts/" + postId + "/comments").bearer(fixtures.bearer(user))
			.json("{}").send();
		assertThat(missing.statusCode()).isEqualTo(400);
		assertThat(write(user, postId, "가".repeat(2000)).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-9#1 없는 글에 쓰면 404 post_not_found")
	void unknownPost() {
		HttpResponse<String> response = write(fixtures.user(), Long.MAX_VALUE, "x");
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(error(response)).isEqualTo("post_not_found");
	}

	@Test
	@DisplayName("TASK-9#B3 삭제된 글에는 댓글을 쓸 수 없다 — 작성자·ADMIN 도 404 post_not_found, 댓글 행이 생기지 않는다")
	void deletedPostRejectsComments() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "삭제될 글");
		board.delete(postId);

		for (User user : List.of(author, fixtures.user(), fixtures.admin())) {
			HttpResponse<String> response = write(user, postId, "늦은 댓글");
			assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
			assertThat(error(response)).isEqualTo("post_not_found");
		}
		assertThat(jdbc.queryForObject("select count(*) from comments where post_id = ?", Long.class, postId))
			.isZero();
	}

	@Test
	@DisplayName("TASK-9#B3 숨김 글에도 댓글을 쓸 수 없다(ADMIN 포함 404 post_not_found)")
	void hiddenPostRejectsComments() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "숨김 글");
		board.hide(postId);

		for (User user : List.of(author, fixtures.admin())) {
			HttpResponse<String> response = write(user, postId, "x");
			assertThat(response.statusCode()).isEqualTo(404);
			assertThat(error(response)).isEqualTo("post_not_found");
		}
	}

	// ---- 목록 ----

	@Test
	@DisplayName("TASK-9#1 GET /posts/{id}/comments: 익명 200, 오래된 순(created_at, id), 페이지 {items, page, size, totalElements, totalPages}")
	void listOldestFirstPaged() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "목록");
		long first = written(author, postId, "1");
		long second = written(fixtures.user(), postId, "2");
		long third = written(author, postId, "3");

		JsonNode all = list(postId);
		assertThat(ids(all)).containsExactly(first, second, third);
		assertThat(all.get("totalElements").asLong()).isEqualTo(3);
		assertThat(all.get("items").get(0).get("content").asString()).isEqualTo("1");
		assertThat(all.get("items").get(0).get("editable").asBoolean()).isFalse();

		JsonNode page1 = list(postId, api.get("/posts/" + postId + "/comments?page=1&size=2"));
		assertThat(ids(page1)).containsExactly(third);
		assertThat(page1.get("page").asInt()).isEqualTo(1);
		assertThat(page1.get("size").asInt()).isEqualTo(2);
		assertThat(page1.get("totalPages").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-9#1 목록의 editable 은 보는 사람 기준(작성자·ADMIN true, 남 false)")
	void listEditableForViewer() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "editable");
		written(author, postId, "내 댓글");

		assertThat(list(postId, api.get("/posts/" + postId + "/comments").bearer(fixtures.bearer(author)))
			.get("items").get(0).get("editable").asBoolean()).isTrue();
		assertThat(list(postId, api.get("/posts/" + postId + "/comments").bearer(fixtures.bearer(fixtures.admin())))
			.get("items").get(0).get("editable").asBoolean()).isTrue();
		assertThat(list(postId, api.get("/posts/" + postId + "/comments").bearer(fixtures.bearer(fixtures.user())))
			.get("items").get(0).get("editable").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("TASK-9#1 목록 page·size 범위 밖이면 400, 없는 글이면 404 post_not_found")
	void listValidation() {
		long postId = board.post(fixtures.user().getId(), null, "범위");
		assertThat(api.get("/posts/" + postId + "/comments?size=0").send().statusCode()).isEqualTo(400);
		assertThat(api.get("/posts/" + postId + "/comments?size=101").send().statusCode()).isEqualTo(400);
		assertThat(api.get("/posts/" + postId + "/comments?page=-1").send().statusCode()).isEqualTo(400);
		HttpResponse<String> missing = api.get("/posts/" + Long.MAX_VALUE + "/comments").send();
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(error(missing)).isEqualTo("post_not_found");
	}

	@Test
	@DisplayName("TASK-9#1 삭제·숨김 글의 댓글 목록은 ADMIN 이 아니면 404, ADMIN 은 200")
	void listOfInvisiblePost() {
		User author = fixtures.user();
		long deleted = board.post(author.getId(), null, "삭제");
		long hidden = board.post(author.getId(), null, "숨김");
		board.delete(deleted);
		board.hide(hidden);

		for (long postId : new long[] { deleted, hidden }) {
			assertThat(api.get("/posts/" + postId + "/comments").send().statusCode()).isEqualTo(404);
			assertThat(api.get("/posts/" + postId + "/comments").bearer(fixtures.bearer(author)).send().statusCode())
				.isEqualTo(404);
			assertThat(api.get("/posts/" + postId + "/comments").bearer(fixtures.bearer(fixtures.admin())).send()
				.statusCode()).isEqualTo(200);
		}
	}

	// ---- 삭제 ----

	@Test
	@DisplayName("TASK-9#1 DELETE /comments/{id}: 작성자 204, 목록에는 자리표시(deleted=true, content·author null)로 남는다")
	void authorDeletesSoftly() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "삭제 대상");
		long keep = written(author, postId, "남는 댓글");
		long gone = written(author, postId, "지울 댓글");

		HttpResponse<String> response = api.delete("/comments/" + gone).bearer(fixtures.bearer(author)).send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		JsonNode page = list(postId);
		assertThat(ids(page)).containsExactly(keep, gone);
		JsonNode placeholder = page.get("items").get(1);
		assertThat(placeholder.get("deleted").asBoolean()).isTrue();
		assertThat(placeholder.get("content").isNull()).isTrue();
		assertThat(placeholder.get("author").isNull()).isTrue();
		assertThat(placeholder.get("editable").asBoolean()).isFalse();
		// soft delete: 행은 남는다
		assertThat(jdbc.queryForObject("select deleted_at is not null from comments where id = ?", Boolean.class, gone))
			.isTrue();
	}

	@Test
	@DisplayName("TASK-9#1 남의 댓글 삭제는 403, ADMIN 은 204")
	void othersForbiddenAdminAllowed() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "권한");
		long id = written(author, postId, "작성자 댓글");

		HttpResponse<String> other = api.delete("/comments/" + id).bearer(fixtures.bearer(fixtures.user())).send();
		assertThat(other.statusCode()).isEqualTo(403);

		assertThat(api.delete("/comments/" + id).bearer(fixtures.bearer(fixtures.admin())).send().statusCode())
			.isEqualTo(204);
	}

	@Test
	@DisplayName("TASK-9#1 없는·이미 삭제된 댓글 삭제는 404 comment_not_found, 익명은 401, 세션은 CSRF 필요")
	void deleteEdgeCases() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "엣지");
		long id = written(author, postId, "x");

		HttpResponse<String> missing = api.delete("/comments/" + Long.MAX_VALUE).bearer(fixtures.bearer(author)).send();
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(error(missing)).isEqualTo("comment_not_found");

		assertThat(api.delete("/comments/" + id).csrf(csrf()).send().statusCode()).isEqualTo(401);
		String session = fixtures.sessionCookie(author);
		assertThat(api.delete("/comments/" + id).cookie(session).send().statusCode()).isEqualTo(403);
		assertThat(api.delete("/comments/" + id).cookie(session).csrf(csrf()).send().statusCode()).isEqualTo(204);

		HttpResponse<String> again = api.delete("/comments/" + id).bearer(fixtures.bearer(author)).send();
		assertThat(again.statusCode()).isEqualTo(404);
		assertThat(error(again)).isEqualTo("comment_not_found");
	}

	@Test
	@DisplayName("TASK-9#1 정지 계정·제한(step-up) 토큰의 댓글 쓰기·삭제는 중앙 필터가 403")
	void centralFiltersApply() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "필터");
		long id = written(author, postId, "x");

		HttpResponse<String> suspended = write(fixtures.suspended(), postId, "x");
		assertThat(suspended.statusCode()).isEqualTo(403);
		assertThat(error(suspended)).isEqualTo("suspended");

		HttpResponse<String> stepUp = api.delete("/comments/" + id).bearer(fixtures.stepUpBearer(author)).send();
		assertThat(stepUp.statusCode()).isEqualTo(403);
		assertThat(error(stepUp)).isEqualTo("step_up_required");
	}

	@Test
	@DisplayName("TASK-9#1 글이 삭제되면 그 글의 댓글은 작성자도 지울 수 없다(404 comment_not_found), ADMIN 은 가능")
	void commentOfDeletedPost() {
		User author = fixtures.user();
		long postId = board.post(author.getId(), null, "글 삭제");
		long id = written(author, postId, "x");
		board.delete(postId);

		HttpResponse<String> response = api.delete("/comments/" + id).bearer(fixtures.bearer(author)).send();
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(error(response)).isEqualTo("comment_not_found");
		assertThat(api.delete("/comments/" + id).bearer(fixtures.bearer(fixtures.admin())).send().statusCode())
			.isEqualTo(204);
	}

}
