package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

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
import com.example.board.support.MutableClock;
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-23#6 (BE-7) 시각은 Clock 빈 하나로: 테스트 시계(MutableClock)를 10년 앞으로 돌리면 글·계정·토큰·카테고리 시각이 모두 그 시계를
 * 따른다(시스템 시계였다면 지금 시각이 찍힌다).
 */
@IntegrationTest
class ClockBeanTest {

	static final Duration FUTURE = Duration.ofDays(3650);

	@LocalServerPort
	int port;

	@Autowired
	MutableClock clock;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	Instant from;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		clock.advance(FUTURE);
		from = clock.instant();
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private void assertFromClock(Instant actual, String what) {
		assertThat(actual).as(what).isBetween(from, clock.instant());
	}

	private Instant at(String sql, Object... args) {
		return jdbc.queryForObject(sql, Timestamp.class, args).toInstant();
	}

	@Test
	@DisplayName("TASK-23#6 글 작성·수정·삭제 시각(응답과 DB)은 Clock 빈을 따른다")
	void postTimes() {
		User author = fixtures.user();
		HttpResponse<String> created = api.post("/posts").bearer(fixtures.bearer(author))
			.json("{\"title\":\"시계\",\"content\":\"본문\"}")
			.send();
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		JsonNode post = AuthClient.json(created);
		long id = post.get("id").asLong();
		assertFromClock(Instant.parse(post.get("createdAt").asString()), "createdAt");
		assertThat(post.get("updatedAt").asString()).isEqualTo(post.get("createdAt").asString());
		assertThat(at("select created_at from posts where id = ?", id))
			.isEqualTo(Instant.parse(post.get("createdAt").asString()));

		JsonNode edited = AuthClient.json(api.patch("/posts/" + id).bearer(fixtures.bearer(author))
			.json("{\"title\":\"고친 시계\"}")
			.send());
		assertFromClock(Instant.parse(edited.get("updatedAt").asString()), "updatedAt");

		assertThat(api.delete("/posts/" + id).bearer(fixtures.bearer(author)).send().statusCode()).isEqualTo(204);
		assertFromClock(at("select deleted_at from posts where id = ?", id), "deleted_at");
	}

	@Test
	@DisplayName("TASK-23#6 가입한 계정·LOCAL 로그인 수단·비밀번호·refresh 토큰·access 토큰(iat) 시각은 Clock 빈을 따른다")
	void accountAndTokenTimes() {
		String email = auth.signedUp();
		Map<String, Object> row = jdbc.queryForMap(
				"select u.created_at as user_created, u.updated_at as user_updated, i.linked_at, c.updated_at as credential_updated"
						+ " from user_identities i join users u on u.id = i.user_id join local_credentials c on c.identity_id = i.id"
						+ " where i.provider = 'LOCAL' and i.subject = ?",
				email.toLowerCase());
		for (String column : row.keySet()) {
			assertFromClock(((Timestamp) row.get(column)).toInstant(), column);
		}

		JsonNode tokens = auth.tokens(email);
		String payload = tokens.get("accessToken").asString().split("\\.")[1];
		long iat = JsonMapper.builder().build()
			.readTree(Base64.getUrlDecoder().decode(payload))
			.get("iat")
			.asLong();
		assertThat(iat).as("access token iat").isBetween(from.getEpochSecond() - 1, clock.instant().getEpochSecond());
		assertFromClock(at("select max(created_at) from refresh_tokens rt join user_identities i on i.user_id = rt.user_id"
				+ " where i.provider = 'LOCAL' and i.subject = ?", email.toLowerCase()), "refresh_tokens.created_at");
	}

	@Test
	@DisplayName("TASK-23#6 access 토큰 만료 판정도 같은 Clock 빈: 시계가 TTL(15분)+여유를 넘기면 401")
	void accessTokenExpiryFollowsClock() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		assertThat(api.get("/auth/me").bearer(bearer).send().statusCode()).isEqualTo(200);
		clock.advance(Duration.ofMinutes(17));
		assertThat(api.get("/auth/me").bearer(bearer).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-23#6 관리자가 만든 카테고리 시각은 Clock 빈을 따른다")
	void categoryTime() {
		String slug = BoardFixtures.unique("clk-");
		HttpResponse<String> created = api.post("/admin/categories").bearer(fixtures.bearer(fixtures.admin()))
			.json("{\"slug\":\"" + slug + "\",\"name\":\"시계\"}")
			.send();
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		assertFromClock(at("select created_at from categories where slug = ?", slug), "categories.created_at");
	}

}
