package com.example.board.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.util.UriComponentsBuilder;

import com.example.board.support.OAuthProviderStub.CognitoUser;
import com.example.board.support.OAuthProviderStub.GitHubUser;
import com.example.board.support.OAuthProviderStub.GoogleUser;
import com.example.board.support.OAuthProviderStub.KakaoUser;
import com.example.board.support.OAuthProviderStub.NaverUser;

/**
 * TASK-4#5 브라우저 대신 OAuth 로그인 흐름을 실제 HTTP 로 몬다. 쿠키 jar 처럼 {@code SESSION} 쿠키를 응답마다 갱신해 들고 다닌다.
 *
 * <ol>
 * <li>시작: {@code GET /api/oauth2/authorization/{p}} (또는 연결 시작 {@code /api/me/identities/link/{p}} → 앱 내부 리다이렉트를
 * 한 번 따라감) → 302 Location(제공자 인가 엔드포인트)에서 state·nonce·redirect_uri 를 꺼낸다.</li>
 * <li>제공자 응답을 stub 한다(코드는 매번 새로).</li>
 * <li>콜백: {@code GET /api/login/oauth2/code/{p}?code&state} 를 같은 세션 쿠키로 부른다.</li>
 * </ol>
 */
public final class OAuthFlow {

	/** {@link IntegrationTest} 의 {@code app.public-base-url}. 테스트 서버의 실제 주소(localhost:port)와 일부러 다르다. */
	public static final String PUBLIC_BASE_URL = IntegrationTest.PUBLIC_BASE_URL;

	private final Api api;

	private final OAuthProviderStub stub;

	private String session;

	public OAuthFlow(Api api, OAuthProviderStub stub) {
		this.api = api;
		this.stub = stub;
	}

	/** 이미 가진 세션(예: 로그인 상태)으로 시작한다. {@code SESSION=...} */
	public OAuthFlow session(String sessionCookie) {
		this.session = sessionCookie;
		return this;
	}

	/** 지금 들고 있는 {@code SESSION=...} (없으면 null). */
	public String session() {
		return session;
	}

	public HttpResponse<String> get(String path) {
		Api.Request request = api.get(path);
		if (session != null) {
			request.cookie(session);
		}
		HttpResponse<String> response = request.send();
		Api.cookie(response, "SESSION").ifPresent(value -> session = value.isEmpty() ? null : "SESSION=" + value);
		return response;
	}

	/** 인가 요청 시작. 302 가 아니면 예외. */
	public Authorization authorize(String startPath) {
		HttpResponse<String> response = get(startPath);
		String location = location(response);
		if (location.startsWith(PUBLIC_BASE_URL + "/api/oauth2/authorization/")) {
			// 연결 시작은 공개 주소 기준으로 앱의 인가 시작 경로로 다시 보낸다 → 브라우저처럼 따라간다.
			response = get(location.substring((PUBLIC_BASE_URL + "/api").length()));
			location = location(response);
		}
		return new Authorization(response, URI.create(location),
				UriComponentsBuilder.fromUriString(location).build().getQueryParams().toSingleValueMap());
	}

	public HttpResponse<String> callback(String provider, String query) {
		return get("/login/oauth2/code/" + provider + "?" + query);
	}

	/** Google 로그인 전체 흐름 (시작 경로 지정). */
	public HttpResponse<String> google(String startPath, GoogleUser user) {
		Authorization authorization = authorize(startPath);
		String code = newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"), user);
		return callback("google", "code=" + code + "&state=" + authorization.param("state"));
	}

	public HttpResponse<String> google(GoogleUser user) {
		return google("/oauth2/authorization/google", user);
	}

	/** GitHub 로그인 전체 흐름 (시작 경로 지정). */
	public HttpResponse<String> github(String startPath, GitHubUser user) {
		Authorization authorization = authorize(startPath);
		String code = newCode();
		stub.stubGitHub(code, authorization.param("redirect_uri"), user);
		return callback("github", "code=" + code + "&state=" + authorization.param("state"));
	}

	public HttpResponse<String> github(GitHubUser user) {
		return github("/oauth2/authorization/github", user);
	}

	/** TASK-5#3 Naver 로그인 전체 흐름 (시작 경로 지정). */
	public HttpResponse<String> naver(String startPath, NaverUser user) {
		Authorization authorization = authorize(startPath);
		String code = newCode();
		stub.stubNaver(code, authorization.param("redirect_uri"), user);
		return callback("naver", "code=" + code + "&state=" + authorization.param("state"));
	}

	public HttpResponse<String> naver(NaverUser user) {
		return naver("/oauth2/authorization/naver", user);
	}

	/** TASK-5#3 Kakao 로그인 전체 흐름 (시작 경로 지정). */
	public HttpResponse<String> kakao(String startPath, KakaoUser user) {
		Authorization authorization = authorize(startPath);
		String code = newCode();
		stub.stubKakao(code, authorization.param("redirect_uri"), user);
		return callback("kakao", "code=" + code + "&state=" + authorization.param("state"));
	}

	public HttpResponse<String> kakao(KakaoUser user) {
		return kakao("/oauth2/authorization/kakao", user);
	}

	/**
	 * TASK-6#3 Cognito 로그인 전체 흐름: 인가 시작 → Hosted UI stub({@code /oauth2/authorize})이 콜백으로 302 → 그 Location 을
	 * 브라우저처럼 따라 콜백을 부른다.
	 */
	public HttpResponse<String> cognito(String startPath, CognitoUser user) {
		Authorization authorization = authorize(startPath);
		String code = newCode();
		stub.stubCognito(code, authorization.param("redirect_uri"), authorization.param("nonce"), user);
		return followHostedUi(authorization, code);
	}

	public HttpResponse<String> cognito(CognitoUser user) {
		return cognito("/oauth2/authorization/cognito", user);
	}

	/** TASK-6#3 Hosted UI 인가 엔드포인트를 stub 하고 따라간 뒤, 돌아온 콜백 Location 을 같은 세션으로 부른다. */
	public HttpResponse<String> followHostedUi(Authorization authorization, String code) {
		String redirectUri = authorization.param("redirect_uri");
		stub.stubCognitoAuthorize(authorization.param("state"), redirectUri, code);
		HttpResponse<String> hostedUi;
		try {
			hostedUi = HttpClient.newHttpClient()
				.send(HttpRequest.newBuilder(authorization.location()).GET().build(), BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
		String callback = location(hostedUi);
		if (!callback.startsWith(redirectUri + "?")) {
			throw new IllegalStateException("Hosted UI 가 redirect_uri 로 돌려보내지 않았다: " + callback);
		}
		return get(callback.substring((PUBLIC_BASE_URL + "/api").length()));
	}

	public static String newCode() {
		return "code" + UUID.randomUUID().toString().replace("-", "");
	}

	public static String location(HttpResponse<?> response) {
		if (response.statusCode() != 302) {
			throw new IllegalStateException("expected 302 but was " + response.statusCode() + " " + response.body());
		}
		return response.headers().firstValue("Location").orElseThrow();
	}

	/** 인가 시작 응답과 그 Location(제공자 인가 엔드포인트) 의 쿼리 파라미터. */
	public record Authorization(HttpResponse<String> response, URI location, Map<String, String> params) {

		public String param(String name) {
			String value = params.get(name);
			return value == null ? null : java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
		}

	}

}
