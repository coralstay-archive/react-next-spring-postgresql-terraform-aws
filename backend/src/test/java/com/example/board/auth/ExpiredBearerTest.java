package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.auth.token.TokenService;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

import tools.jackson.databind.JsonNode;

/**
 * TASK-24#1 (BE-15) 만료된 Bearer 헤더는 refresh·logout·공개 GET 에서 무시한다(익명으로 계속). 보호된 API 와 서명이 틀린 토큰은 그대로
 * 401 이다.
 */
@IntegrationTest
class ExpiredBearerTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JwtProperties jwtProperties;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	/** 앱과 같은 비밀값·발급자로, 한 시간 전에 발급해 이미 만료된 access 토큰. */
	private String expiredAccessToken(long userId) {
		Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
		return new JwtTokenService(jwtProperties, past)
			.issueAccessToken(new AuthUser(userId, Role.USER, UserStatus.ACTIVE));
	}

	/** 다른 비밀값으로 서명한(위조) 토큰. 만료되지 않았다. */
	private String forgedAccessToken(long userId) {
		JwtProperties other = new JwtProperties("another-secret-that-is-long-enough-0123456789", jwtProperties.issuer(),
				jwtProperties.accessTokenTtl(), jwtProperties.refreshTokenTtl());
		return new JwtTokenService(other, Clock.systemUTC())
			.issueAccessToken(new AuthUser(userId, Role.USER, UserStatus.ACTIVE));
	}

	private long userIdOf(String rawRefreshToken) {
		return jdbc.queryForObject("select user_id from refresh_tokens where token_hash = ?", Long.class,
				TokenService.hash(rawRefreshToken));
	}

	private int liveTokensInFamilyOf(String rawRefreshToken) {
		return jdbc.queryForObject("""
				select count(*) from refresh_tokens where revoked_at is null
				and family_id = (select family_id from refresh_tokens where token_hash = ?)""", Integer.class,
				TokenService.hash(rawRefreshToken));
	}

	@Test
	@DisplayName("TASK-24#1 TASK-24#B1 만료된 access 토큰으로 로그아웃해도 204 이고 refresh family 가 폐기된다")
	void logoutWithExpiredAccessTokenRevokesFamily() {
		String refresh = auth.tokens(auth.signedUp()).get("refreshToken").asString();
		String expired = expiredAccessToken(userIdOf(refresh));

		HttpResponse<String> response = api.post("/auth/logout")
			.bearer(expired)
			.json("{\"refreshToken\":" + AuthClient.quote(refresh) + "}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(refresh)).isZero();
	}

	@Test
	@DisplayName("TASK-24#1 TASK-24#B1 만료 토큰의 주인이 아닌 사람의 refresh 토큰은 폐기하지 않는다(204 는 같다)")
	void logoutWithExpiredTokenIgnoresOthersRefresh() {
		String mine = auth.tokens(auth.signedUp()).get("refreshToken").asString();
		String others = auth.tokens(auth.signedUp()).get("refreshToken").asString();

		HttpResponse<String> response = api.post("/auth/logout")
			.bearer(expiredAccessToken(userIdOf(mine)))
			.json("{\"refreshToken\":" + AuthClient.quote(others) + "}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		assertThat(liveTokensInFamilyOf(others)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-24#1 만료된 access 토큰을 붙인 refresh 도 200 으로 로테이션된다")
	void refreshWithExpiredAccessToken() {
		JsonNode tokens = auth.tokens(auth.signedUp());
		String refresh = tokens.get("refreshToken").asString();

		HttpResponse<String> response = api.post("/auth/token/refresh")
			.bearer(expiredAccessToken(userIdOf(refresh)))
			.json("{\"refreshToken\":" + AuthClient.quote(refresh) + "}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("refreshToken").asString()).isNotEqualTo(refresh);
	}

	@Test
	@DisplayName("TASK-24#1 공개 GET(글 목록·카테고리·태그·CSRF) 은 만료된 Bearer 를 무시하고 익명으로 200")
	void publicGetsIgnoreExpiredBearer() {
		String expired = expiredAccessToken(1L);

		for (String path : new String[] { "/posts", "/categories", "/tags", "/auth/csrf" }) {
			HttpResponse<String> response = api.get(path).bearer(expired).send();
			assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(200);
		}
	}

	@Test
	@DisplayName("TASK-24#1 보호된 API 는 만료된 Bearer 면 그대로 401 unauthorized")
	void protectedApiStillRejectsExpiredBearer() {
		HttpResponse<String> response = api.get("/auth/me").bearer(expiredAccessToken(1L)).send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("unauthorized");
	}

	@Test
	@DisplayName("TASK-24#1 서명이 틀린 토큰은 공개 GET·refresh·logout 에서도 401 (만료만 봐 준다)")
	void forgedTokenIsStill401() {
		String forged = forgedAccessToken(1L);

		assertThat(api.get("/posts").bearer(forged).send().statusCode()).isEqualTo(401);
		assertThat(api.post("/auth/logout").bearer(forged).send().statusCode()).isEqualTo(401);
		assertThat(api.post("/auth/token/refresh").bearer(forged).json("{\"refreshToken\":\"x\"}").send().statusCode())
			.isEqualTo(401);
	}

}
