package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.example.board.user.User;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;

/** TASK-8#2 글 목록·상세 (공개) + TASK-8#B2 삭제·숨김 글 제외. */
@IntegrationTest
class PostReadTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private JsonNode ok(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	@Test
	@DisplayName("TASK-8#2 익명 목록: 카테고리 필터 + 최신순 + 페이지 응답 {items, page, size, totalElements, totalPages}")
	void anonymousListPage() {
		User author = fixtures.user();
		String slug = board.category();
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		long first = board.post(author.getId(), slug, "첫째", "본문1", base.minusSeconds(30), "java", "spring");
		long second = board.post(author.getId(), slug, "둘째", "본문2", base.minusSeconds(20));
		long third = board.post(author.getId(), slug, "셋째", "본문3", base.minusSeconds(10));
		board.post(author.getId(), board.category(), "다른 카테고리", "x", base);

		JsonNode page0 = ok(api.get("/posts?category=" + slug + "&page=0&size=2&sort=latest").send());
		assertThat(ids(page0)).containsExactly(third, second);
		assertThat(page0.get("page").asInt()).isZero();
		assertThat(page0.get("size").asInt()).isEqualTo(2);
		assertThat(page0.get("totalElements").asLong()).isEqualTo(3);
		assertThat(page0.get("totalPages").asInt()).isEqualTo(2);

		JsonNode page1 = ok(api.get("/posts?category=" + slug + "&page=1&size=2").send());
		assertThat(ids(page1)).containsExactly(first);
		JsonNode item = page1.get("items").get(0);
		assertThat(item.get("title").asString()).isEqualTo("첫째");
		assertThat(item.get("excerpt").asString()).isEqualTo("본문1");
		assertThat(item.get("author").get("id").asLong()).isEqualTo(author.getId());
		assertThat(item.get("author").get("nickname").asString()).isEqualTo("tester");
		assertThat(item.get("category").get("slug").asString()).isEqualTo(slug);
		assertThat(item.get("category").get("name").asString()).isEqualTo("카테고리 " + slug);
		assertThat(item.get("tags").valueStream().map(JsonNode::asString).toList()).containsExactly("java", "spring");
		assertThat(item.get("createdAt").asString()).isNotBlank();
		assertThat(item.get("updatedAt").asString()).isNotBlank();
		assertThat(item.has("content")).isFalse();
	}

	@Test
	@DisplayName("TASK-8#2 태그 필터(정규화된 이름으로 비교)와 카테고리 필터를 같이 쓴다")
	void tagFilter() {
		User author = fixtures.user();
		String slug = board.category();
		String tag = BoardFixtures.unique("t");
		long tagged = board.post(author.getId(), slug, "태그 있음", tag);
		board.post(author.getId(), slug, "태그 없음");
		long otherCategory = board.post(author.getId(), board.category(), "다른 카테고리", tag);

		assertThat(ids(ok(api.get("/posts?category=" + slug + "&tag=" + tag.toUpperCase()).send())))
			.containsExactly(tagged);
		assertThat(ids(ok(api.get("/posts?tag=" + tag).send()))).containsExactlyInAnyOrder(tagged, otherCategory);
	}

	@Test
	@DisplayName("TASK-8#2 excerpt 는 공백을 접은 본문 앞부분(최대 200자, 넘으면 …)")
	void excerpt() {
		User author = fixtures.user();
		String slug = board.category();
		board.post(author.getId(), slug, "긴 글", "가나\n\n  다라 " + "a".repeat(300), Instant.now());

		String excerpt = ok(api.get("/posts?category=" + slug).send()).get("items").get(0).get("excerpt").asString();
		assertThat(excerpt).startsWith("가나 다라 aaa").endsWith("…");
		assertThat(excerpt.length()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-8#2 page·size·sort 검증: size 1~50, page ≥ 0, 지원하지 않는 sort(latest·popular 외), 숫자가 아니면 400")
	void pagingValidation() {
		assertThat(api.get("/posts?size=50").send().statusCode()).isEqualTo(200);
		assertThat(api.get("/posts").send().statusCode()).isEqualTo(200);
		for (String query : List.of("size=51", "size=0", "page=-1", "sort=oldest", "page=abc", "size=x")) {
			HttpResponse<String> response = api.get("/posts?" + query).send();
			assertThat(response.statusCode()).as(query).isEqualTo(400);
			assertThat(AuthClient.json(response).get("status").asInt()).as(query).isEqualTo(400);
		}
	}

	@Test
	@DisplayName("TASK-8#2 기본 size 는 20")
	void defaultSize() {
		JsonNode page = ok(api.get("/posts").send());
		assertThat(page.get("size").asInt()).isEqualTo(20);
		assertThat(page.get("page").asInt()).isZero();
	}

	@Test
	@DisplayName("TASK-8#2 목록은 작성자·카테고리·태그를 한꺼번에 읽는다(N+1 없음): 글 수와 상관없이 SQL 문 수가 일정")
	void noNPlusOne() {
		String slug = board.category();
		for (int i = 0; i < 6; i++) {
			board.post(fixtures.user().getId(), slug, "글" + i, BoardFixtures.unique("a"), BoardFixtures.unique("b"));
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		boolean enabled = statistics.isStatisticsEnabled();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			JsonNode page = ok(api.get("/posts?category=" + slug + "&size=10").send());
			long statements = statistics.getPrepareStatementCount();

			assertThat(page.get("items")).hasSize(6);
			page.get("items").forEach(item -> assertThat(item.get("tags")).hasSize(2));
			// 페이지 조회 + count + 작성자·카테고리·태그 일괄 조회 (+ 카테고리 조회 여유)
			assertThat(statements).isLessThanOrEqualTo(5);
		}
		finally {
			statistics.setStatisticsEnabled(enabled);
		}
	}

	@Test
	@DisplayName("TASK-8#2 상세: 익명 공개, 본문·작성자·카테고리·태그 + 보는 사람 기준 editable")
	void detail() {
		User author = fixtures.user();
		String slug = board.category();
		long id = board.post(author.getId(), slug, "상세", "전체 본문", Instant.now(), "tag-a");

		JsonNode anonymous = ok(api.get("/posts/" + id).send());
		assertThat(anonymous.get("id").asLong()).isEqualTo(id);
		assertThat(anonymous.get("title").asString()).isEqualTo("상세");
		assertThat(anonymous.get("content").asString()).isEqualTo("전체 본문");
		assertThat(anonymous.get("author").get("id").asLong()).isEqualTo(author.getId());
		assertThat(anonymous.get("category").get("slug").asString()).isEqualTo(slug);
		assertThat(anonymous.get("tags").get(0).asString()).isEqualTo("tag-a");
		assertThat(anonymous.get("createdAt").asString()).isNotBlank();
		assertThat(anonymous.get("editable").asBoolean()).isFalse();
		assertThat(anonymous.get("deleted").asBoolean()).isFalse();
		assertThat(anonymous.get("hidden").asBoolean()).isFalse();

		assertThat(ok(api.get("/posts/" + id).bearer(fixtures.bearer(author)).send()).get("editable").asBoolean())
			.isTrue();
		assertThat(ok(api.get("/posts/" + id).cookie(fixtures.sessionCookie(author)).send()).get("editable")
			.asBoolean()).isTrue();
		assertThat(ok(api.get("/posts/" + id).bearer(fixtures.bearer(fixtures.user())).send()).get("editable")
			.asBoolean()).isFalse();
		assertThat(ok(api.get("/posts/" + id).bearer(fixtures.bearer(fixtures.admin())).send()).get("editable")
			.asBoolean()).isTrue();
	}

	@Test
	@DisplayName("TASK-8#2 카테고리 없는 글은 category=null, 없는 글은 404 JSON")
	void detailWithoutCategoryAndNotFound() {
		long id = board.post(fixtures.user().getId(), null, "무분류");
		assertThat(ok(api.get("/posts/" + id).send()).get("category").isNull()).isTrue();

		HttpResponse<String> missing = api.get("/posts/999999999").send();
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(AuthClient.json(missing).get("error").asString()).isEqualTo("post_not_found");
	}

	@Test
	@DisplayName("TASK-8#B2 삭제·숨김 글은 익명·일반 사용자(작성자 포함) 목록에서 빠지고 상세는 404")
	void hiddenAndDeletedExcluded() {
		User author = fixtures.user();
		String slug = board.category();
		long visible = board.post(author.getId(), slug, "보임");
		long hidden = board.post(author.getId(), slug, "숨김");
		long deleted = board.post(author.getId(), slug, "삭제");
		board.hide(hidden);
		board.delete(deleted);

		JsonNode anonymous = ok(api.get("/posts?category=" + slug).send());
		assertThat(ids(anonymous)).containsExactly(visible);
		assertThat(anonymous.get("totalElements").asLong()).isEqualTo(1);
		assertThat(ids(ok(api.get("/posts?category=" + slug + "&includeHidden=true").bearer(fixtures.bearer(author))
			.send()))).as("ADMIN 이 아니면 includeHidden 은 무시").containsExactly(visible);

		for (long id : List.of(hidden, deleted)) {
			assertThat(api.get("/posts/" + id).send().statusCode()).isEqualTo(404);
			assertThat(api.get("/posts/" + id).bearer(fixtures.bearer(author)).send().statusCode()).isEqualTo(404);
		}
	}

	@Test
	@DisplayName("TASK-8#B2 ADMIN 은 상세에서 삭제·숨김 글을 deleted/hidden 표시와 함께 보고, 목록은 includeHidden=true 일 때만 포함")
	void adminSeesHiddenAndDeleted() {
		User author = fixtures.user();
		String admin = fixtures.bearer(fixtures.admin());
		String slug = board.category();
		long visible = board.post(author.getId(), slug, "보임");
		long hidden = board.post(author.getId(), slug, "숨김");
		long deleted = board.post(author.getId(), slug, "삭제");
		board.hide(hidden);
		board.delete(deleted);

		JsonNode hiddenDetail = ok(api.get("/posts/" + hidden).bearer(admin).send());
		assertThat(hiddenDetail.get("hidden").asBoolean()).isTrue();
		assertThat(hiddenDetail.get("deleted").asBoolean()).isFalse();
		assertThat(hiddenDetail.get("editable").asBoolean()).isTrue();
		JsonNode deletedDetail = ok(api.get("/posts/" + deleted).bearer(admin).send());
		assertThat(deletedDetail.get("deleted").asBoolean()).isTrue();
		assertThat(deletedDetail.get("editable").asBoolean()).as("삭제된 글은 수정 대상이 아니다").isFalse();

		assertThat(ids(ok(api.get("/posts?category=" + slug).bearer(admin).send()))).containsExactly(visible);
		JsonNode all = ok(api.get("/posts?category=" + slug + "&includeHidden=true").bearer(admin).send());
		assertThat(ids(all)).containsExactlyInAnyOrder(visible, hidden, deleted);
		all.get("items").forEach(item -> {
			long id = item.get("id").asLong();
			assertThat(item.get("hidden").asBoolean()).isEqualTo(id == hidden);
			assertThat(item.get("deleted").asBoolean()).isEqualTo(id == deleted);
		});
	}

}
