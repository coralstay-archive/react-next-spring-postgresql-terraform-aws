package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.FailingInsert;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GoogleUser;
import com.example.board.support.OAuthProviderStub.NaverUser;
import com.example.board.user.User;

/**
 * TASK-25#1 TASK-25#B1 브라우저 OAuth 흐름은 어떤 실패에도 프론트 콜백으로 302 한다 — 500·JSON 을 브라우저에 내지 않는다. 성공 핸들러 안의
 * 예외(BE-19)는 {@code ?error=server_error}. 실패 핸들러·매퍼 예외·state 불일치·제공자 오류 파라미터·모르는 제공자도 모두 콜백으로 돌아간다.
 */
@IntegrationTest
class OAuth2CallbackFailureTest {

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	static final String LINK_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/account/?error=";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	@BeforeEach
	void setUp() {
		api = new Api(port);
	}

	private OAuthFlow flow() {
		return new OAuthFlow(api, stub);
	}

	private Integer identityCount(String subject) {
		return jdbc.queryForObject("select count(*) from user_identities where subject = ?", Integer.class, subject);
	}

	@Test
	@DisplayName("TASK-25#1 TASK-25#B1 성공 핸들러에서 계정 처리(DB)가 실패하면 500 이 아니라 /auth/callback/?error=server_error 로 302, 세션은 인증되지 않는다")
	void successHandlerFailureOnLogin() {
		String sub = "g-" + UUID.randomUUID();
		OAuthFlow flow = flow();
		HttpResponse<String> callback;
		try (FailingInsert ignored = FailingInsert.on(jdbc, "user_identities", "subject", sub)) {
			callback = flow.google(new GoogleUser(sub, null, false, "Broken"));
		}

		assertThat(callback.statusCode()).isEqualTo(302);
		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "server_error");
		assertThat(identityCount(sub)).isZero();
		if (flow.session() != null) {
			assertThat(api.get("/auth/me").cookie(flow.session()).send().statusCode()).isEqualTo(401);
		}
	}

	@Test
	@DisplayName("TASK-25#1 TASK-25#B1 연결 모드의 성공 핸들러 예외는 계정 화면 /account/?error=server_error 로 302, 로그인 세션은 유지된다")
	void successHandlerFailureOnLink() {
		User me = fixtures.user();
		String session = fixtures.sessionCookie(me);
		String sub = "g-" + UUID.randomUUID();
		OAuthFlow flow = flow().session(session);
		HttpResponse<String> callback;
		try (FailingInsert ignored = FailingInsert.on(jdbc, "user_identities", "subject", sub)) {
			callback = flow.google("/me/identities/link/google", new GoogleUser(sub, null, false, "Broken"));
		}

		assertThat(OAuthFlow.location(callback)).isEqualTo(LINK_ERROR + "server_error");
		assertThat(identityCount(sub)).isZero();
		assertThat(api.get("/auth/me").cookie(flow.session()).send().statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-25#B1 실패 핸들러(토큰 엔드포인트 오류)도 콜백으로 302")
	void failureHandler() {
		OAuthFlow flow = flow();
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogleTokenError(code, "invalid_grant");

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_grant");
	}

	@Test
	@DisplayName("TASK-25#B1 매퍼 예외(Naver 응답에 id 없음)도 콜백으로 302 ?error=invalid_user_info")
	void mapperFailure() {
		HttpResponse<String> callback = flow().naver(new NaverUser(null, null, "nick", null));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info");
	}

	@Test
	@DisplayName("TASK-25#B1 state 불일치도 콜백으로 302 ?error=authorization_request_not_found")
	void stateMismatch() {
		OAuthFlow flow = flow();
		flow.authorize("/oauth2/authorization/google");

		HttpResponse<String> callback = flow.callback("google", "code=" + OAuthFlow.newCode() + "&state=forged");

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "authorization_request_not_found");
	}

	@Test
	@DisplayName("TASK-25#B1 제공자 오류 파라미터(?error=server_error 등)도 콜백으로 302, 인가 요청 없이 온 콜백도 302")
	void providerErrorParameter() {
		OAuthFlow flow = flow();
		Authorization authorization = flow.authorize("/oauth2/authorization/github");

		HttpResponse<String> callback = flow.callback("github",
				"error=temporarily_unavailable&error_description=down&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "temporarily_unavailable");
		assertThat(OAuthFlow.location(flow().callback("github", "code=x&state=y")))
			.isEqualTo(LOGIN_ERROR + "authorization_request_not_found");
	}

	@Test
	@DisplayName("TASK-25#B1 모르는 제공자로 시작해도 콜백으로 302 ?error=unknown_provider")
	void unknownProvider() {
		assertThat(OAuthFlow.location(flow().get("/oauth2/authorization/facebook")))
			.isEqualTo(LOGIN_ERROR + "unknown_provider");
	}

}
