package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.example.board.auth.token.TokenService;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** TASK-3#5 POST /api/auth/token/refresh: 로테이션 + 재사용 감지 시 family 전체 폐기. */
@IntegrationTest
class RefreshTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private Map<String, Object> row(String raw) {
		return jdbc.queryForMap("select id, family_id, revoked_at, replaced_by from refresh_tokens where token_hash = ?",
				TokenService.hash(raw));
	}

	private int liveTokensInFamilyOf(String raw) {
		return jdbc.queryForObject("""
				select count(*) from refresh_tokens where revoked_at is null
				and family_id = (select family_id from refresh_tokens where token_hash = ?)""", Integer.class,
				TokenService.hash(raw));
	}

	private long userId(String email) {
		return jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
	}

	private static void assert401(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
		assertThat(response.body()).contains("\"error\":\"invalid_refresh_token\"").doesNotContain("accessToken");
	}

	@Test
	@DisplayName("TASK-3#5 refresh 하면 새 access+refresh 를 같은 family 로 발급하고, 이전 토큰은 replaced_by·revoked_at 이 기록된다")
	void rotates() {
		String email = auth.signedUp();
		String old = auth.tokens(email).get("refreshToken").asString();

		HttpResponse<String> response = auth.refresh(old);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
		assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
		String next = body.get("refreshToken").asString();
		assertThat(next).isNotEqualTo(old);
		Map<String, Object> oldRow = row(old);
		Map<String, Object> nextRow = row(next);
		assertThat(nextRow.get("family_id")).isEqualTo(oldRow.get("family_id"));
		assertThat(oldRow.get("revoked_at")).isNotNull();
		assertThat(((Number) oldRow.get("replaced_by")).longValue()).isEqualTo(((Number) nextRow.get("id")).longValue());
		assertThat(nextRow.get("revoked_at")).isNull();

		HttpResponse<String> me = api.get("/auth/me").bearer(body.get("accessToken").asString()).send();
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(AuthClient.json(me).get("id").asLong()).isEqualTo(userId(email));

		// 새 토큰으로 다시 로테이션할 수 있다
		assertThat(auth.refresh(next).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-3#5 refresh 는 Bearer·CSRF 없이 호출한다(세션 쿠키가 같이 와도 쓰지 않는다)")
	void noCsrfNeeded() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		String refresh = auth.tokens(email).get("refreshToken").asString();

		HttpResponse<String> response = api.post("/auth/token/refresh")
			.cookie(session)
			.json("{\"refreshToken\":" + AuthClient.quote(refresh) + "}")
			.send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
	}

	@Test
	@DisplayName("TASK-3#5 모르는·만료된·폐기된 refresh 토큰은 401")
	void invalidTokensAre401() {
		assert401(auth.refresh("no-such-token"));

		String expired = auth.tokens(auth.signedUp()).get("refreshToken").asString();
		jdbc.update("update refresh_tokens set expires_at = now() - interval '1 second' where token_hash = ?",
				TokenService.hash(expired));
		assert401(auth.refresh(expired));

		JsonNode loggedOut = auth.tokens(auth.signedUp());
		api.post("/auth/logout")
			.bearer(loggedOut.get("accessToken").asString())
			.json("{\"refreshToken\":" + AuthClient.quote(loggedOut.get("refreshToken").asString()) + "}")
			.send();
		assert401(auth.refresh(loggedOut.get("refreshToken").asString()));
	}

	@Test
	@DisplayName("TASK-3#5 refreshToken 이 비어 있으면 400")
	void blankIs400() {
		assertThat(api.post("/auth/token/refresh").json("{}").send().statusCode()).isEqualTo(400);
		assertThat(auth.refresh("").statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-3#5 TASK-3#B3 이미 로테이션된 refresh 토큰을 재사용하면 401 + family 전체 폐기(정상적으로 받은 새 토큰도 못 쓴다)")
	void reuseRevokesFamily() {
		String old = auth.tokens(auth.signedUp()).get("refreshToken").asString();
		String next = AuthClient.json(auth.refresh(old)).get("refreshToken").asString();

		assert401(auth.refresh(old));

		assertThat(liveTokensInFamilyOf(old)).isZero();
		assert401(auth.refresh(next));
	}

	@Test
	@DisplayName("TASK-3#5 TASK-3#B3 같은 토큰으로 동시에 refresh 하면 하나만 성공하고, 나머지는 재사용으로 감지돼 family 가 폐기된다")
	void concurrentRefresh() throws Exception {
		String token = auth.tokens(auth.signedUp()).get("refreshToken").asString();
		int n = 5;
		ExecutorService executor = Executors.newFixedThreadPool(n);
		CountDownLatch start = new CountDownLatch(1);
		List<HttpResponse<String>> responses = new ArrayList<>();
		try {
			List<Future<HttpResponse<String>>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				futures.add(executor.submit(() -> {
					start.await();
					return auth.refresh(token);
				}));
			}
			start.countDown();
			for (Future<HttpResponse<String>> future : futures) {
				responses.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}

		List<HttpResponse<String>> ok = responses.stream().filter(r -> r.statusCode() == 200).toList();
		assertThat(ok).hasSize(1);
		assertThat(responses).filteredOn(r -> r.statusCode() != 200).allSatisfy(RefreshTest::assert401);
		assertThat(jdbc.queryForObject("""
				select count(*) from refresh_tokens
				where family_id = (select family_id from refresh_tokens where token_hash = ?)""", Integer.class,
				TokenService.hash(token)))
			.as("로테이션은 한 번만 일어난다")
			.isEqualTo(2);
		assertThat(liveTokensInFamilyOf(token)).isZero();
		assert401(auth.refresh(AuthClient.json(ok.get(0)).get("refreshToken").asString()));
	}

	@Test
	@DisplayName("TASK-3#5 refresh 때 계정 상태를 다시 본다: 정지되면 403 suspended + family 폐기")
	void suspendedUserCannotRefresh() {
		String email = auth.signedUp();
		String refresh = auth.tokens(email).get("refreshToken").asString();
		jdbc.update("update users set status = 'SUSPENDED' where id = ?", userId(email));

		HttpResponse<String> response = auth.refresh(refresh);

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"suspended\"").doesNotContain("accessToken");
		assertThat(liveTokensInFamilyOf(refresh)).isZero();
	}

	@Test
	@DisplayName("TASK-3#5 새 access 토큰에는 refresh 시점의 최신 역할이 실린다")
	void newAccessTokenCarriesCurrentRole() {
		String email = auth.signedUp();
		String refresh = auth.tokens(email).get("refreshToken").asString();
		jdbc.update("update users set role = 'ADMIN' where id = ?", userId(email));

		String access = AuthClient.json(auth.refresh(refresh)).get("accessToken").asString();

		assertThat(api.get("/test/admin-only").bearer(access).send().statusCode()).isEqualTo(200);
	}

}
