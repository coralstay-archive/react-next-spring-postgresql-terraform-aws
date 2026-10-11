package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GoogleUser;

/** TASK-4#5 WireMock 으로 제공자 쪽 실패(토큰 오류, userinfo 장애, 코드 재사용)를 재현한다. */
@IntegrationTest
class OAuth2ProviderFailureTest {

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		flow = new OAuthFlow(new Api(port), stub);
	}

	@Test
	@DisplayName("TASK-4#5 토큰 엔드포인트가 invalid_grant 를 주면 ?error=invalid_grant, 세션은 인증되지 않는다")
	void tokenEndpointError() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogleTokenError(code, "invalid_grant");

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_grant");
		assertThat(stub.tokenRequests("google", code)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-4#5 GitHub /user 가 500 이면 ?error=invalid_user_info_response, 계정을 만들지 않는다")
	void userInfoFailure() {
		Authorization authorization = flow.authorize("/oauth2/authorization/github");
		String code = OAuthFlow.newCode();
		stub.stubGitHubUserFailure(code, authorization.param("redirect_uri"));
		int users = jdbc.queryForObject("select count(*) from users", Integer.class);

		HttpResponse<String> callback = flow.callback("github", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info_response");
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(users);
	}

	@Test
	@DisplayName("TASK-4#5 같은 콜백(state)을 다시 보내면 인가 요청이 이미 소비돼 ?error=authorization_request_not_found, 토큰 교환은 한 번뿐")
	void callbackReplay() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser("g-replay-" + code, null, false, "Replay"));
		String query = "code=" + code + "&state=" + authorization.param("state");
		String anonymous = flow.session();
		assertThat(OAuthFlow.location(flow.callback("google", query))).endsWith("?status=ok");

		HttpResponse<String> replay = new OAuthFlow(new Api(port), stub).session(anonymous).callback("google", query);

		assertThat(OAuthFlow.location(replay)).isEqualTo(LOGIN_ERROR + "authorization_request_not_found");
		assertThat(stub.tokenRequests("google", code)).isEqualTo(1);
	}

}
