package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.NaverUser;

/**
 * TASK-25#2 OAuth 실패(실패 핸들러)와 매퍼 예외의 원인이 WARN 로그에 남는다. 인가 코드·state·제공자 토큰·이메일·제공자 원문 응답(본문,
 * error_description)은 남지 않는다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class OAuth2FailureLogTest {

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		flow = new OAuthFlow(new Api(port), stub);
	}

	/** 이번 테스트가 남긴 OAuth 로그 줄. */
	private static List<String> lines(CapturedOutput output, String marker) {
		return output.getAll().lines().filter(line -> line.contains(marker)).toList();
	}

	@Test
	@DisplayName("TASK-25#2 userinfo 401 실패: WARN 에 등록 id·오류 코드·예외 사슬·HTTP 상태, 코드·state·access token·응답 본문은 없다")
	void userInfoFailureLogged(CapturedOutput output) {
		Authorization authorization = flow.authorize("/oauth2/authorization/kakao");
		String code = OAuthFlow.newCode();
		stub.stubKakaoUserFailure(code, authorization.param("redirect_uri"));

		HttpResponse<String> callback = flow.callback("kakao", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info_response");
		List<String> lines = lines(output, "OAuth 로그인 실패");
		assertThat(lines).singleElement().satisfies(line -> {
			assertThat(line).contains("WARN")
				.contains("registration=kakao")
				.contains("error=invalid_user_info_response")
				.contains("OAuth2AuthenticationException")
				.contains("status=401");
		});
		assertThat(output.getAll()).doesNotContain(code)
			.doesNotContain("kakao-at-" + code)
			.doesNotContain(authorization.param("state"))
			.doesNotContain("this access token does not exist");
	}

	@Test
	@DisplayName("TASK-25#2 토큰 오류(Naver 200 + error): WARN 에 오류 코드, error_description 원문·인가 코드는 없다")
	void tokenErrorLogged(CapturedOutput output) {
		Authorization authorization = flow.authorize("/oauth2/authorization/naver");
		String code = OAuthFlow.newCode();
		stub.stubNaverTokenError(code, "invalid_request");

		HttpResponse<String> callback = flow.callback("naver", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).startsWith(LOGIN_ERROR);
		assertThat(lines(output, "OAuth 로그인 실패")).singleElement()
			.satisfies(line -> assertThat(line).contains("WARN").contains("registration=naver"));
		assertThat(output.getAll()).doesNotContain(code).doesNotContain("no valid data in session");
	}

	@Test
	@DisplayName("TASK-25#2 매퍼 예외(Naver 응답에 id 없음): WARN 에 등록 id·예외 클래스, 이메일·이름·응답 원문은 없다")
	void mapperFailureLogged(CapturedOutput output) {
		String email = "mapper-" + UUID.randomUUID() + "@naver.test";

		HttpResponse<String> callback = flow.naver(new NaverUser(null, email, "secret-nick", "Secret Name"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info");
		assertThat(lines(output, "OAuth 사용자 정보 매핑 실패")).singleElement().satisfies(line -> {
			assertThat(line).contains("WARN").contains("registration=naver").contains("IllegalArgumentException");
		});
		assertThat(output.getAll()).doesNotContain(email).doesNotContain("secret-nick").doesNotContain("Secret Name");
	}

}
