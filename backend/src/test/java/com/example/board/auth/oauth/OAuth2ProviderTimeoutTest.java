package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GitHubUser;
import com.example.board.support.OAuthProviderStub.GoogleUser;

/**
 * TASK-25#3 BE-21 실제 oauth2Login 흐름이 타임아웃 클라이언트를 쓰는지: 제공자(WireMock)가 응답을 {@link #DELAY_MILLIS} 동안 주지 않으면
 * 읽기 타임아웃({@link IntegrationTest#OAUTH_READ_TIMEOUT}) 뒤 프론트 콜백으로 302 한다. 타임아웃이 없으면 지연이 끝날 때까지 매달린다.
 */
@IntegrationTest
class OAuth2ProviderTimeoutTest {

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	static final Duration READ_TIMEOUT = IntegrationTest.OAUTH_READ_TIMEOUT_DURATION;

	/** 읽기 타임아웃의 두 배. TASK-35: 통합 테스트 읽기 타임아웃을 3s → 10s 로 늘리면서 지연도 8s → 20s 로 함께 늘렸다. */
	static final int DELAY_MILLIS = (int) READ_TIMEOUT.multipliedBy(2).toMillis();

	/**
	 * 지연보다 짧으면 타임아웃이 끊은 것이다(타임아웃이 없으면 지연이 끝나는 20s 이후에야 응답). 읽기 타임아웃 10s 위로 부하 여유 7s,
	 * 지연과는 3s 차이.
	 */
	static final Duration BOUND = Duration.ofMillis(DELAY_MILLIS - 3_000);

	/** 읽기 타임아웃보다 빨리 끝나면 타임아웃이 아니라 다른 이유(즉시 오류)로 끝난 것이다. */
	static final Duration AT_LEAST = READ_TIMEOUT.minusMillis(500);

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		flow = new OAuthFlow(new Api(port), stub);
	}

	@Test
	@DisplayName("TASK-25#3 토큰 엔드포인트가 응답하지 않으면 타임아웃 뒤 ?error=invalid_token_response")
	void tokenTimeout() {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogleTokenDelayed(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser("g-" + UUID.randomUUID(), null, false, "Slow"), DELAY_MILLIS);
		Instant start = Instant.now();

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_token_response");
		assertThat(Duration.between(start, Instant.now())).isLessThan(BOUND).isGreaterThanOrEqualTo(AT_LEAST);
	}

	@Test
	@DisplayName("TASK-25#3 userinfo 가 응답하지 않으면 타임아웃 뒤 ?error=invalid_user_info_response")
	void userInfoTimeout() {
		Authorization authorization = flow.authorize("/oauth2/authorization/github");
		String code = OAuthFlow.newCode();
		long id = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
		stub.stubGitHubUserDelayed(code, authorization.param("redirect_uri"), new GitHubUser(id, "slow", null, null),
				DELAY_MILLIS);
		Instant start = Instant.now();

		HttpResponse<String> callback = flow.callback("github", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info_response");
		assertThat(Duration.between(start, Instant.now())).isLessThan(BOUND).isGreaterThanOrEqualTo(AT_LEAST);
	}

	@Test
	@DisplayName("TASK-25#3 JWKS 가 응답하지 않으면 타임아웃 뒤 ?error=invalid_id_token")
	void jwksTimeout() throws Exception {
		Authorization authorization = flow.authorize("/oauth2/authorization/google");
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser("g-" + UUID.randomUUID(), null, false, "Slow"));
		HttpResponse<String> callback;
		Instant start = Instant.now();
		try (AutoCloseable ignored = stub.delayGoogleJwks(DELAY_MILLIS)) {
			callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));
		}

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_id_token");
		assertThat(Duration.between(start, Instant.now())).isLessThan(BOUND).isGreaterThanOrEqualTo(AT_LEAST);
	}

}
