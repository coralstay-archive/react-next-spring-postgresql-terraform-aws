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

/** TASK-12#2 관리자 글 숨김·숨김 해제·삭제 복구, 감사 로그 조회. */
@IntegrationTest
class AdminPostApiTest {

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

	User author;

	String category;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		admin = fixtures.admin();
		adminBearer = fixtures.bearer(admin);
		author = fixtures.user();
		category = board.category();
	}

	private HttpResponse<String> action(long postId, String action, String reason) {
		Api.Request request = api.post("/admin/posts/" + postId + "/" + action).bearer(adminBearer);
		if (reason != null) {
			request.json("{\"reason\":" + AuthClient.quote(reason) + "}");
		}
		return request.send();
	}

	private JsonNode ok(long postId, String action) {
		HttpResponse<String> response = action(postId, action, null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private List<Long> publicList() {
		JsonNode page = AuthClient.json(api.get("/posts?category=" + category).send());
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	private int publicDetail(long postId) {
		return api.get("/posts/" + postId).send().statusCode();
	}

	private List<Map<String, Object>> auditRows(long postId) {
		return jdbc.queryForList(
				"select admin_id, action, target_type, reason from admin_actions where target_type = 'POST' and target_id = ? order by id",
				postId);
	}

	@Test
	@DisplayName("TASK-12#2 hide 하면 공개 목록·상세에서 사라지고(404), unhide 하면 돌아온다. 멱등, 응답은 글 상태")
	void hideAndUnhide() {
		long post = board.post(author.getId(), category, "숨길 글");
		assertThat(publicList()).contains(post);

		JsonNode hidden = ok(post, "hide");
		assertThat(hidden.get("id").asLong()).isEqualTo(post);
		assertThat(hidden.get("hidden").asBoolean()).isTrue();
		assertThat(hidden.get("deleted").asBoolean()).isFalse();
		assertThat(hidden.get("hiddenAt").isNull()).isFalse();
		String firstHiddenAt = hidden.get("hiddenAt").asString();
		assertThat(ok(post, "hide").get("hiddenAt").asString()).isEqualTo(firstHiddenAt);

		assertThat(publicList()).doesNotContain(post);
		assertThat(publicDetail(post)).isEqualTo(404);
		// ADMIN 은 여전히 상세를 본다
		assertThat(api.get("/posts/" + post).bearer(adminBearer).send().statusCode()).isEqualTo(200);

		JsonNode visible = ok(post, "unhide");
		assertThat(visible.get("hidden").asBoolean()).isFalse();
		assertThat(visible.get("hiddenAt").isNull()).isTrue();
		assertThat(ok(post, "unhide").get("hidden").asBoolean()).isFalse();
		assertThat(publicList()).contains(post);
		assertThat(publicDetail(post)).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-12#2 restore 는 삭제된 글의 deleted_at 을 지워 다시 보이게 한다. 숨김은 따로 유지된다")
	void restoreDeletedPost() {
		long post = board.post(author.getId(), category, "삭제된 글");
		board.delete(post);
		assertThat(publicDetail(post)).isEqualTo(404);

		JsonNode restored = ok(post, "restore");
		assertThat(restored.get("deleted").asBoolean()).isFalse();
		assertThat(restored.get("deletedAt").isNull()).isTrue();
		assertThat(ok(post, "restore").get("deleted").asBoolean()).isFalse();
		assertThat(publicDetail(post)).isEqualTo(200);
		assertThat(publicList()).contains(post);

		long both = board.post(author.getId(), category, "삭제+숨김");
		board.delete(both);
		board.hide(both);
		JsonNode stillHidden = ok(both, "restore");
		assertThat(stillHidden.get("deleted").asBoolean()).isFalse();
		assertThat(stillHidden.get("hidden").asBoolean()).isTrue();
		assertThat(publicDetail(both)).isEqualTo(404);
	}

	@Test
	@DisplayName("TASK-12#2 복구된 글은 작성자가 다시 수정할 수 있다")
	void restoredPostIsEditableAgain() {
		long post = board.post(author.getId(), category, "복구 후 수정");
		board.delete(post);
		ok(post, "restore");
		HttpResponse<String> edit = api.patch("/posts/" + post)
			.bearer(fixtures.bearer(author))
			.json("{\"title\":\"고친 제목\"}")
			.send();
		assertThat(edit.statusCode()).as(edit.body()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-12#2 없는 글은 404. 모든 관리 액션은 감사 로그에 사유와 함께 남는다")
	void notFoundAndAudit() {
		assertThat(action(Long.MAX_VALUE, "hide", null).statusCode()).isEqualTo(404);
		assertThat(action(Long.MAX_VALUE, "unhide", null).statusCode()).isEqualTo(404);
		assertThat(action(Long.MAX_VALUE, "restore", null).statusCode()).isEqualTo(404);

		long post = board.post(author.getId(), category, "감사");
		assertThat(action(post, "hide", "광고").statusCode()).isEqualTo(200);
		assertThat(action(post, "unhide", null).statusCode()).isEqualTo(200);
		assertThat(action(post, "restore", "  ").statusCode()).isEqualTo(200);

		List<Map<String, Object>> rows = auditRows(post);
		assertThat(rows).extracting(row -> row.get("action")).containsExactly("POST_HIDE", "POST_UNHIDE", "POST_RESTORE");
		assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("admin_id", admin.getId()));
		assertThat(rows.get(0)).containsEntry("reason", "광고");
		assertThat(rows.get(2).get("reason")).isNull();
		assertThat(action(post, "hide", "x".repeat(501)).statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-12#2 GET /admin/actions: 최근순 감사 로그(관리자 닉네임 포함), 페이지 크기 검증")
	void listActions() {
		long post = board.post(author.getId(), category, "로그 조회");
		action(post, "hide", "사유1");
		action(post, "unhide", null);

		HttpResponse<String> response = api.get("/admin/actions?size=2").bearer(adminBearer).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode page = AuthClient.json(response);
		assertThat(page.get("size").asInt()).isEqualTo(2);
		JsonNode latest = page.get("items").get(0);
		assertThat(latest.get("action").asString()).isEqualTo("POST_UNHIDE");
		assertThat(latest.get("targetType").asString()).isEqualTo("POST");
		assertThat(latest.get("targetId").asLong()).isEqualTo(post);
		assertThat(latest.get("adminId").asLong()).isEqualTo(admin.getId());
		assertThat(latest.get("adminNickname").asString()).isEqualTo(admin.getNickname());
		assertThat(latest.get("createdAt").isNull()).isFalse();
		assertThat(page.get("items").get(1).get("reason").asString()).isEqualTo("사유1");

		assertThat(api.get("/admin/actions?size=0").bearer(adminBearer).send().statusCode()).isEqualTo(400);
	}

}
