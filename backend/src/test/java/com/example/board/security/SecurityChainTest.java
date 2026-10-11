package com.example.board.security;

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

import com.example.board.auth.AuthUser;
import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/** TASK-2#2 SecurityFilterChain 하나에서 Bearer 헤더면 JWT, 아니면 세션(Spring Session JDBC)으로 인증한다. */
@IntegrationTest
class SecurityChainTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JwtProperties jwtProperties;

	@Autowired
	JdbcTemplate jdbcTemplate;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	@Test
	@DisplayName("TASK-2#2 유효한 Bearer 토큰이면 JWT 경로로 인증되고 principal 이 AuthUser 다")
	void bearerAuthenticates() {
		User user = fixtures.user();

		HttpResponse<String> response = api.get("/test/whoami").bearer(fixtures.bearer(user)).send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"id\":" + user.getId()).contains("\"role\":\"USER\"")
			.contains("\"status\":\"ACTIVE\"");
	}

	@Test
	@DisplayName("TASK-2#2 Bearer 가 없으면 SESSION 쿠키(Spring Session JDBC)로 인증되고 principal 이 AuthUser 다")
	void sessionAuthenticates() {
		User admin = fixtures.admin();

		HttpResponse<String> response = api.get("/test/whoami").cookie(fixtures.sessionCookie(admin)).send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"id\":" + admin.getId()).contains("\"role\":\"ADMIN\"");
	}

	@Test
	@DisplayName("TASK-2#2 미인증 요청은 로그인 페이지로 리다이렉트하지 않고 401 JSON 이다")
	void anonymousGets401Json() {
		HttpResponse<String> response = api.get("/test/whoami").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				ct -> assertThat(ct).startsWith("application/json"));
		assertThat(response.body()).contains("\"status\":401");
	}

	@Test
	@DisplayName("TASK-2#2 권한이 없으면 403 JSON 이다")
	void forbiddenGets403Json() {
		HttpResponse<String> response = api.get("/test/admin-only").bearer(fixtures.bearer(fixtures.user())).send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				ct -> assertThat(ct).startsWith("application/json"));
		assertThat(response.body()).contains("\"status\":403");
	}

	@Test
	@DisplayName("TASK-2#2 잘못된 Bearer 토큰은 유효한 세션 쿠키가 같이 와도 401 이다")
	void invalidBearerWithSessionIs401() {
		String session = fixtures.sessionCookie(fixtures.user());

		HttpResponse<String> response = api.get("/test/whoami").cookie(session).bearer("not-a-jwt").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
				h -> assertThat(h).startsWith("Bearer"));
	}

	@Test
	@DisplayName("TASK-2#2 다른 키로 서명한 토큰과 만료된 토큰은 401 이다")
	void forgedOrExpiredTokenIs401() {
		User user = fixtures.user();
		JwtTokenService otherKey = new JwtTokenService(new JwtProperties(
				"another-secret-another-secret-another-secret!!", jwtProperties.issuer(), Duration.ofMinutes(5), Duration.ofDays(14)));
		JwtTokenService pastClock = new JwtTokenService(jwtProperties,
				Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC));

		assertThat(api.get("/test/whoami").bearer(otherKey.issueAccessToken(AuthUser.from(user))).send().statusCode())
			.isEqualTo(401);
		assertThat(api.get("/test/whoami").bearer(pastClock.issueAccessToken(AuthUser.from(user))).send().statusCode())
			.isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-2#2 Bearer 와 세션이 같이 오면 Bearer 사용자로 인증되고 세션을 만들거나 바꾸지 않는다")
	void bearerWinsAndDoesNotTouchSession() {
		User bearerUser = fixtures.user();
		User sessionUser = fixtures.user();
		String session = fixtures.sessionCookie(sessionUser);
		Integer sessionsBefore = jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class);

		HttpResponse<String> response = api.get("/test/whoami").cookie(session).bearer(fixtures.bearer(bearerUser)).send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"id\":" + bearerUser.getId());
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class))
			.isEqualTo(sessionsBefore);
	}

	@Test
	@DisplayName("TASK-2#2 Bearer 요청만으로는 세션이 생기지 않는다")
	void bearerOnlyIsStateless() {
		HttpResponse<String> response = api.get("/test/whoami").bearer(fixtures.bearer(fixtures.user())).send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
	}

	@Test
	@DisplayName("TASK-2#2 세션 쿠키는 SESSION 이름에 HttpOnly·SameSite=Lax 다")
	void sessionCookieAttributes() {
		// 보안 체인은 Bearer 요청에서 세션을 쓰지 않지만, 애플리케이션 코드가 세션을 직접 만들면 Spring Session 이
		// 쿠키를 내보낸다. 로그인 API(TASK-3) 전이라 이 테스트 엔드포인트로 그 쿠키 속성을 확인한다.
		HttpResponse<String> response = api.get("/test/session/touch").bearer(fixtures.bearer(fixtures.user())).send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(Api.setCookie(response, "SESSION")).hasValueSatisfying(cookie -> assertThat(cookie)
			.containsIgnoringCase("HttpOnly")
			.containsIgnoringCase("SameSite=Lax"));
	}

	@Test
	@DisplayName("TASK-2#2 /api/actuator/health 는 인증 없이 UP 이고 세부 정보는 숨긴다")
	void healthIsPublicWithoutDetails() {
		HttpResponse<String> response = api.get("/actuator/health").send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"db\"");
	}

}
