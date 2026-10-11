package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** TASK-9#4 sort=popular: 좋아요 많은 순, 같으면 최신순(created_at, id 내림차순). */
@IntegrationTest
class PostPopularSortTest {

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

	private JsonNode ok(String path) {
		var response = api.get(path).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private static List<Long> ids(JsonNode page) {
		return page.get("items").valueStream().map(item -> item.get("id").asLong()).toList();
	}

	private void likes(long postId, int count) {
		for (int i = 0; i < count; i++) {
			User fan = fixtures.user();
			var response = api.post("/posts/" + postId + "/like").bearer(fixtures.bearer(fan)).send();
			assertThat(response.statusCode()).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-9#4 GET /posts?sort=popular: like_count 내림차순, 동점은 최신순, 페이징 유지")
	void popularOrder() {
		String slug = board.category();
		long authorId = fixtures.user().getId();
		Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		long oldPopular = board.post(authorId, slug, "오래됐지만 인기", "본문", base.minusSeconds(300));
		long tieOlder = board.post(authorId, slug, "동점 오래됨", "본문", base.minusSeconds(200));
		long tieNewer = board.post(authorId, slug, "동점 최신", "본문", base.minusSeconds(100));
		long newest = board.post(authorId, slug, "최신, 좋아요 없음", "본문", base);
		likes(oldPopular, 3);
		likes(tieOlder, 1);
		likes(tieNewer, 1);

		JsonNode all = ok("/posts?category=" + slug + "&sort=popular");
		assertThat(ids(all)).containsExactly(oldPopular, tieNewer, tieOlder, newest);
		assertThat(all.get("items").get(0).get("likeCount").asLong()).isEqualTo(3);

		assertThat(ids(ok("/posts?category=" + slug + "&sort=latest"))).containsExactly(newest, tieNewer, tieOlder,
				oldPopular);
		assertThat(ids(ok("/posts?category=" + slug + "&sort=POPULAR&page=1&size=2"))).containsExactly(tieOlder, newest);
	}

	@Test
	@DisplayName("TASK-9#4 인기순도 삭제·숨김 글은 뺀다, 같은 created_at 이면 id 내림차순")
	void popularExcludesInvisibleAndBreaksTiesById() {
		String slug = board.category();
		long authorId = fixtures.user().getId();
		Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		long first = board.post(authorId, slug, "a", "본문", at);
		long second = board.post(authorId, slug, "b", "본문", at);
		long hidden = board.post(authorId, slug, "숨김", "본문", at);
		likes(hidden, 2);
		board.hide(hidden);

		assertThat(ids(ok("/posts?category=" + slug + "&sort=popular"))).containsExactly(second, first);
	}

	@Test
	@DisplayName("TASK-9#4 보이는 글 인기순 부분 인덱스(posts_visible_popular_ix)가 있다")
	void popularIndex() {
		String definition = jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'posts_visible_popular_ix'",
				String.class);
		assertThat(definition).contains("like_count DESC").contains("created_at DESC").contains("id DESC")
			.contains("deleted_at IS NULL").contains("hidden_at IS NULL");
	}

}
