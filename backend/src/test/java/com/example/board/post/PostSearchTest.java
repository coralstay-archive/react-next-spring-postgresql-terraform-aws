package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;

/**
 * TASK-10#3 GET /api/posts?q= 검색: search_tsv @@ websearch_to_tsquery('simple', q) OR title/content ILIKE '%q%'.
 * 테스트 컨텍스트가 DB 를 공유하므로 각 테스트는 자기 카테고리 안에서 확인한다.
 */
@IntegrationTest
class PostSearchTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	Api api;

	long authorId;

	String slug;

	Instant base;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		authorId = fixtures.user().getId();
		slug = board.category();
		base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private HttpResponse<String> search(String q, String extra) {
		return api.get("/posts?category=" + slug + "&q=" + enc(q) + extra).send();
	}

	private List<Long> found(String q) {
		return found(q, "");
	}

	private List<Long> found(String q, String extra) {
		HttpResponse<String> response = search(q, extra);
		assertThat(response.statusCode()).as(q + " → " + response.body()).isEqualTo(200);
		return AuthClient.json(response).get("items").valueStream().map(item -> item.get("id").asLong()).toList();
	}

	private long post(String title, String content, int secondsAgo, String... tags) {
		return board.post(authorId, slug, title, content, base.minusSeconds(secondsAgo), tags);
	}

	@Test
	@DisplayName("TASK-10#3 영문 단어: 제목·본문에서 대소문자 무시로 찾고, 결과는 기본 최신순")
	void englishWords() {
		long inTitle = post("Spring Boot guide", "intro", 30);
		long inContent = post("가이드", "we use spring boot here", 20);
		post("Terraform", "aws only", 10);

		assertThat(found("boot")).containsExactly(inContent, inTitle);
		assertThat(found("SPRING")).containsExactly(inContent, inTitle);
		assertThat(found("spring guide")).as("websearch: 모든 단어(AND) 또는 문구 그대로").containsExactly(inTitle);
		assertThat(found("terraform aws")).hasSize(1);
		assertThat(found("kubernetes")).isEmpty();
	}

	@Test
	@DisplayName("TASK-10#3 한국어 부분 일치: '스프링' 으로 '스프링부트' 를 제목·본문에서 찾는다")
	void koreanPartial() {
		long title = post("스프링부트 입문", "본문", 30);
		long content = post("자바", "오늘은 스프링부트로 게시판을 만든다", 20);
		post("리액트", "넥스트", 10);

		assertThat(found("스프링")).containsExactly(content, title);
		assertThat(found("게시판을")).containsExactly(content);
		assertThat(found("  스프링  ")).as("앞뒤 공백은 자른다").containsExactly(content, title);
	}

	@Test
	@DisplayName("TASK-10#3 q 는 category·tag·sort=popular 와 함께 쓴다(AND), 페이징도 유지")
	void combinedWithFilters() {
		String tag = BoardFixtures.unique("t");
		long tagged = post("검색 대상 A", "본문", 40, tag);
		long taggedPopular = post("검색 대상 B", "본문", 30, tag);
		long untagged = post("검색 대상 C", "본문", 20);
		post("무관한 글", "본문", 10, tag);
		String otherSlug = board.category();
		long otherCategory = board.post(authorId, otherSlug, "검색 대상 D", "본문", base, tag);
		like(taggedPopular, 2);

		assertThat(found("검색", "&tag=" + tag)).containsExactly(taggedPopular, tagged);
		assertThat(found("검색", "&sort=popular")).containsExactly(taggedPopular, untagged, tagged);
		assertThat(found("검색", "&sort=popular&tag=" + tag + "&size=1&page=1")).containsExactly(tagged);
		var all = api.get("/posts?q=" + enc("검색 대상") + "&tag=" + tag).send();
		assertThat(AuthClient.json(all).get("items").valueStream().map(i -> i.get("id").asLong()).toList())
			.containsExactlyInAnyOrder(tagged, taggedPopular, otherCategory);
		assertThat(AuthClient.json(search("검색", "&size=2")).get("totalElements").asLong()).isEqualTo(3);
	}

	private void like(long postId, int count) {
		for (int i = 0; i < count; i++) {
			var response = api.post("/posts/" + postId + "/like").bearer(fixtures.bearer(fixtures.user())).send();
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-10#3 특수문자 %·_·\\ 는 ILIKE 와일드카드가 아니라 글자 그대로 찾는다")
	void wildcardsAreEscaped() {
		long percent = post("진행률 100% 완료", "본문", 60);
		post("진행률 1000 완료", "본문", 50);
		long underscore = post("snake a_b case", "본문", 40);
		post("snake axb case", "본문", 30);
		long backslash = post("경로 c:\\temp 확인", "본문", 20);

		assertThat(found("100%")).containsExactly(percent);
		assertThat(found("a_b")).containsExactly(underscore);
		assertThat(found("%%")).isEmpty();
		assertThat(found("__")).isEmpty();
		assertThat(found("c:\\temp")).containsExactly(backslash);
	}

	@Test
	@DisplayName("TASK-10#3 websearch 문법(따옴표·-제외·or)과 짝이 안 맞는 따옴표도 400/500 없이 처리한다")
	void websearchSyntax() {
		long both = post("alpha beta", "본문", 30);
		long alphaOnly = post("alpha gamma", "본문", 20);

		assertThat(found("alpha -beta")).containsExactly(alphaOnly);
		assertThat(found("\"alpha beta\"")).containsExactly(both);
		assertThat(found("beta or gamma")).containsExactly(alphaOnly, both);
		assertThat(found("\"alpha")).containsExactly(alphaOnly, both);
		assertThat(found("!!")).isEmpty();
		long quoted = post("it's fine", "본문", 10);
		assertThat(found("it's")).as("작은따옴표는 바인드 파라미터로 넘어가야 한다").containsExactly(quoted);
		assertThat(found("'); drop table posts; --")).isEmpty();
	}

	@Test
	@DisplayName("TASK-10#3 검색해도 삭제·숨김 글은 빠진다(ADMIN 의 includeHidden=true 일 때만 포함)")
	void invisibleExcluded() {
		long visible = post("숨김검사 보이는 글", "본문", 30);
		long hidden = post("숨김검사 숨긴 글", "본문", 20);
		long deleted = post("숨김검사 지운 글", "본문", 10);
		board.hide(hidden);
		board.delete(deleted);

		assertThat(found("숨김검사")).containsExactly(visible);
		assertThat(found("숨김검사", "&includeHidden=true")).containsExactly(visible);
		var admin = api.get("/posts?category=" + slug + "&q=" + enc("숨김검사") + "&includeHidden=true")
			.bearer(fixtures.bearer(fixtures.admin())).send();
		assertThat(AuthClient.json(admin).get("items").valueStream().map(i -> i.get("id").asLong()).toList())
			.containsExactly(deleted, hidden, visible);
	}

	@Test
	@DisplayName("TASK-10#3 q 검증: 자른 뒤 2~100자, 벗어나면 400 validation, 비었으면 검색하지 않는다")
	void validation() {
		long any = post("아무 글", "본문", 10);
		for (String q : List.of("a", " 가 ", "x".repeat(101))) {
			HttpResponse<String> response = search(q, "");
			assertThat(response.statusCode()).as(q).isEqualTo(400);
			assertThat(AuthClient.json(response).get("error").asString()).as(q).isEqualTo("validation");
		}
		assertThat(found("")).containsExactly(any);
		assertThat(found("   ")).containsExactly(any);
		assertThat(found("ab")).isEmpty();
		assertThat(search("x".repeat(100), "").statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-10#3 검색 목록도 N+1 없이 페이지 조회 + count + 일괄 조회")
	void noNPlusOne() {
		for (int i = 0; i < 6; i++) {
			post("일괄조회 " + i, "본문", i, BoardFixtures.unique("a"), BoardFixtures.unique("b"));
		}
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		boolean enabled = statistics.isStatisticsEnabled();
		statistics.setStatisticsEnabled(true);
		try {
			statistics.clear();
			HttpResponse<String> response = search("일괄조회", "&size=10");
			long statements = statistics.getPrepareStatementCount();

			JsonNode page = AuthClient.json(response);
			assertThat(page.get("items")).hasSize(6);
			page.get("items").forEach(item -> assertThat(item.get("tags")).hasSize(2));
			assertThat(statements).isLessThanOrEqualTo(5);
		}
		finally {
			statistics.setStatisticsEnabled(enabled);
		}
	}

}
