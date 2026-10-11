package com.example.board.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.test.context.DynamicPropertyRegistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-4#5 OAuth 제공자 stub (WireMock). 통합 테스트 컨텍스트 하나에 하나만 뜨고, 앱의 Google·GitHub 등록이 이 서버의
 * 엔드포인트(토큰, userinfo, JWKS)를 보도록 {@link #registerProperties} 가 설정을 덮는다.
 *
 * <ul>
 * <li>Google(OIDC): {@code /google/token} 이 이 stub 의 RSA 키로 서명한 id_token 을 주고, 그 공개키를 {@code /google/jwks} 로
 * 서빙한다. 앱은 실제 OIDC 처럼 서명·iss·aud·nonce 를 검증한다.</li>
 * <li>GitHub(OAuth2): {@code /github/token} + {@code /github/user}.</li>
 * <li>TASK-5#3 Naver: {@code /naver/token} + {@code /naver/me}, Kakao: {@code /kakao/token} + {@code /kakao/me}.</li>
 * </ul>
 * 인가 엔드포인트({@code /google/authorize}, {@code /github/authorize})는 브라우저가 가는 곳이라 stub 하지 않는다. 테스트는
 * 앱이 내려준 Location 만 검사하고 바로 콜백을 부른다. 응답은 인가 코드(테스트마다 고유)로 구분하므로 테스트끼리 섞이지 않는다.
 */
public final class OAuthProviderStub implements DisposableBean {

	public static final String GOOGLE_CLIENT_ID = "test-google-client";

	public static final String GITHUB_CLIENT_ID = "test-github-client";

	public static final String NAVER_CLIENT_ID = "test-naver-client";

	public static final String NAVER_CLIENT_SECRET = "test-naver-secret";

	public static final String KAKAO_CLIENT_ID = "test-kakao-rest-key";

	public static final String KAKAO_CLIENT_SECRET = "test-kakao-secret";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final WireMockServer server;

	private final RSAKey signingKey;

	public OAuthProviderStub() {
		this.server = new WireMockServer(options().dynamicPort());
		this.server.start();
		this.signingKey = newKey("stub-key");
		this.server.stubFor(get(urlPathEqualTo("/google/jwks"))
			.willReturn(okJson(new JWKSet(this.signingKey.toPublicJWK()).toString())));
	}

	/** 앱 설정을 stub 으로 돌린다. {@code DynamicPropertyRegistrar} 로 공유 컨텍스트에 한 번 등록된다. */
	public void registerProperties(DynamicPropertyRegistry registry) {
		String google = "app.oauth2.registrations.google.";
		registry.add(google + "client-id", () -> GOOGLE_CLIENT_ID);
		registry.add(google + "client-secret", () -> "test-google-secret");
		registry.add(google + "authorization-uri", () -> url("/google/authorize"));
		registry.add(google + "token-uri", () -> url("/google/token"));
		registry.add(google + "user-info-uri", () -> url("/google/userinfo"));
		registry.add(google + "jwk-set-uri", () -> url("/google/jwks"));
		registry.add(google + "issuer-uri", this::googleIssuer);
		String github = "app.oauth2.registrations.github.";
		registry.add(github + "client-id", () -> GITHUB_CLIENT_ID);
		registry.add(github + "client-secret", () -> "test-github-secret");
		registry.add(github + "authorization-uri", () -> url("/github/authorize"));
		registry.add(github + "token-uri", () -> url("/github/token"));
		registry.add(github + "user-info-uri", () -> url("/github/user"));
		// TASK-5#3 Naver·Kakao
		String naver = "app.oauth2.registrations.naver.";
		registry.add(naver + "client-id", () -> NAVER_CLIENT_ID);
		registry.add(naver + "client-secret", () -> NAVER_CLIENT_SECRET);
		registry.add(naver + "authorization-uri", () -> url("/naver/authorize"));
		registry.add(naver + "token-uri", () -> url("/naver/token"));
		registry.add(naver + "user-info-uri", () -> url("/naver/me"));
		String kakao = "app.oauth2.registrations.kakao.";
		registry.add(kakao + "client-id", () -> KAKAO_CLIENT_ID);
		registry.add(kakao + "client-secret", () -> KAKAO_CLIENT_SECRET);
		registry.add(kakao + "authorization-uri", () -> url("/kakao/authorize"));
		registry.add(kakao + "token-uri", () -> url("/kakao/token"));
		registry.add(kakao + "user-info-uri", () -> url("/kakao/me"));
	}

	public String url(String path) {
		return server.baseUrl() + path;
	}

	public String googleIssuer() {
		return url("/google");
	}

	/** 앱이 신뢰하지 않는 키 (서명 검증 실패 테스트용). */
	public static RSAKey newKey(String keyId) {
		try {
			return new RSAKeyGenerator(2048).keyID(keyId).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Google 토큰·userinfo 응답을 stub 한다. id_token 은 {@code nonce}(앱이 인가 요청에 실은 값)와 사용자 클레임을 담아
	 * {@code key} 로 서명한다. 토큰 요청은 앱이 보낸 {@code redirect_uri} 가 {@code expectedRedirectUri} 일 때만 맞는다.
	 */
	public void stubGoogle(String code, String expectedRedirectUri, String nonce, GoogleUser user, RSAKey key) {
		String accessToken = "google-at-" + code;
		Map<String, Object> token = new LinkedHashMap<>();
		token.put("access_token", accessToken);
		token.put("token_type", "Bearer");
		token.put("expires_in", 3599);
		token.put("scope", "openid https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/userinfo.email");
		token.put("id_token", idToken(user, nonce, key));
		server.stubFor(post(urlPathEqualTo("/google/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(token))));
		server.stubFor(get(urlPathEqualTo("/google/userinfo"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(okJson(JSON.writeValueAsString(user.claims()))));
	}

	public void stubGoogle(String code, String expectedRedirectUri, String nonce, GoogleUser user) {
		stubGoogle(code, expectedRedirectUri, nonce, user, signingKey);
	}

	/** GitHub 토큰·{@code /user} 응답을 stub 한다. */
	public void stubGitHub(String code, String expectedRedirectUri, GitHubUser user) {
		String accessToken = "github-at-" + code;
		server.stubFor(post(urlPathEqualTo("/github/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(
					Map.of("access_token", accessToken, "token_type", "bearer", "scope", "read:user")))));
		server.stubFor(get(urlPathEqualTo("/github/user"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(aResponse().withHeader("Content-Type", "application/json")
				.withBody(JSON.writeValueAsString(user.attributes()))));
	}

	/** Google 토큰 엔드포인트가 OAuth2 오류(400 {@code {"error": errorCode}})를 돌려준다 (예: 코드 재사용 invalid_grant). */
	public void stubGoogleTokenError(String code, String errorCode) {
		server.stubFor(post(urlPathEqualTo("/google/token")).withRequestBody(containing("code=" + code))
			.willReturn(aResponse().withStatus(400)
				.withHeader("Content-Type", "application/json")
				.withBody(JSON.writeValueAsString(Map.of("error", errorCode)))));
	}

	/** GitHub 토큰은 정상, {@code /user} 가 500 이다. */
	public void stubGitHubUserFailure(String code, String expectedRedirectUri) {
		String accessToken = "github-at-" + code;
		server.stubFor(post(urlPathEqualTo("/github/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(
					Map.of("access_token", accessToken, "token_type", "bearer", "scope", "read:user")))));
		server.stubFor(get(urlPathEqualTo("/github/user"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(aResponse().withStatus(500)));
	}

	/**
	 * TASK-5#3 Naver 토큰·프로필 응답을 stub 한다. 토큰 요청은 client_id·client_secret 이 form 파라미터(client_secret_post)로 올 때만
	 * 맞는다. Naver 처럼 {@code expires_in} 은 문자열, 프로필은 {@code response} 로 감싼다.
	 */
	public void stubNaver(String code, String expectedRedirectUri, NaverUser user) {
		String accessToken = "naver-at-" + code;
		server.stubFor(post(urlPathEqualTo("/naver/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("client_id=" + NAVER_CLIENT_ID))
			.withRequestBody(containing("client_secret=" + NAVER_CLIENT_SECRET))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(Map.of("access_token", accessToken, "refresh_token",
					"naver-rt-" + code, "token_type", "bearer", "expires_in", "3600")))));
		server.stubFor(get(urlPathEqualTo("/naver/me"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(okJson(JSON.writeValueAsString(user.attributes()))));
	}

	/** TASK-5#3 Naver 토큰 오류: Naver 는 HTTP 200 에 {@code {"error","error_description"}} 를 준다(access_token 없음). */
	public void stubNaverTokenError(String code, String errorCode) {
		server.stubFor(post(urlPathEqualTo("/naver/token")).withRequestBody(containing("code=" + code))
			.willReturn(okJson(JSON.writeValueAsString(
					Map.of("error", errorCode, "error_description", "no valid data in session")))));
	}

	/** TASK-5#3 Kakao 토큰·사용자 정보 응답을 stub 한다. 토큰 요청은 client_secret_post 일 때만 맞는다. */
	public void stubKakao(String code, String expectedRedirectUri, KakaoUser user) {
		String accessToken = "kakao-at-" + code;
		Map<String, Object> token = new LinkedHashMap<>();
		token.put("token_type", "bearer");
		token.put("access_token", accessToken);
		token.put("expires_in", 21599);
		token.put("refresh_token", "kakao-rt-" + code);
		token.put("refresh_token_expires_in", 5183999);
		token.put("scope", user.email() == null ? "profile_nickname" : "account_email profile_nickname");
		server.stubFor(post(urlPathEqualTo("/kakao/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("client_id=" + KAKAO_CLIENT_ID))
			.withRequestBody(containing("client_secret=" + KAKAO_CLIENT_SECRET))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(token))));
		server.stubFor(get(urlPathEqualTo("/kakao/me"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(okJson(JSON.writeValueAsString(user.attributes()))));
	}

	/** TASK-5#3 Kakao 토큰은 정상, 사용자 정보가 401(만료·폐기된 토큰, Kakao 오류 {@code code:-401}). */
	public void stubKakaoUserFailure(String code, String expectedRedirectUri) {
		String accessToken = "kakao-at-" + code;
		server.stubFor(post(urlPathEqualTo("/kakao/token"))
			.withRequestBody(containing("code=" + code))
			.withRequestBody(containing("redirect_uri=" + URLEncoder.encode(expectedRedirectUri, StandardCharsets.UTF_8)))
			.willReturn(okJson(JSON.writeValueAsString(
					Map.of("access_token", accessToken, "token_type", "bearer", "expires_in", 21599)))));
		server.stubFor(get(urlPathEqualTo("/kakao/me"))
			.withHeader("Authorization", equalTo("Bearer " + accessToken))
			.willReturn(aResponse().withStatus(401)
				.withHeader("Content-Type", "application/json")
				.withBody(JSON.writeValueAsString(Map.of("msg", "this access token does not exist", "code", -401)))));
	}

	/** 토큰 엔드포인트가 받은 요청 수 (code 기준). */
	public int tokenRequests(String provider, String code) {
		return server.findAll(com.github.tomakehurst.wiremock.client.WireMock
			.postRequestedFor(urlPathEqualTo("/" + provider + "/token"))
			.withRequestBody(containing("code=" + code))).size();
	}

	private String idToken(GoogleUser user, String nonce, RSAKey key) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(googleIssuer())
			.subject(user.sub())
			.audience(GOOGLE_CLIENT_ID)
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plusSeconds(3600)))
			.claim("nonce", nonce);
		user.claims().forEach((name, value) -> {
			if (!"sub".equals(name)) {
				claims.claim(name, value);
			}
		});
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
					claims.build());
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public void destroy() {
		server.stop();
	}

	/** Google 사용자 (id_token·userinfo 클레임). */
	public record GoogleUser(String sub, String email, boolean emailVerified, String name) {

		Map<String, Object> claims() {
			Map<String, Object> claims = new LinkedHashMap<>();
			claims.put("sub", sub);
			if (email != null) {
				claims.put("email", email);
				claims.put("email_verified", emailVerified);
			}
			if (name != null) {
				claims.put("name", name);
			}
			return claims;
		}

	}

	/** GitHub {@code /user} 응답. email 은 공개 이메일이 없으면 null 이다. */
	public record GitHubUser(long id, String login, String name, String email) {

		Map<String, Object> attributes() {
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("id", id);
			attributes.put("login", login);
			attributes.put("name", name);
			attributes.put("email", email);
			return attributes;
		}

	}

	/** TASK-5#3 Naver 프로필. null 인 항목은 동의하지 않은 것으로 보고 응답에서 뺀다. */
	public record NaverUser(String id, String email, String nickname, String name) {

		Map<String, Object> attributes() {
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("id", id);
			if (email != null) {
				response.put("email", email);
			}
			if (nickname != null) {
				response.put("nickname", nickname);
			}
			if (name != null) {
				response.put("name", name);
			}
			return Map.of("resultcode", "00", "message", "success", "response", response);
		}

	}

	/** TASK-5#3 Kakao 사용자 정보. email 이 null 이면 이메일 미동의(email_needs_agreement=true). */
	public record KakaoUser(long id, String email, boolean emailVerified, String nickname) {

		Map<String, Object> attributes() {
			Map<String, Object> account = new LinkedHashMap<>();
			account.put("profile_nickname_needs_agreement", false);
			account.put("profile", Map.of("nickname", nickname, "is_default_nickname", false));
			account.put("has_email", true);
			account.put("email_needs_agreement", email == null);
			if (email != null) {
				account.put("is_email_valid", true);
				account.put("is_email_verified", emailVerified);
				account.put("email", email);
			}
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("id", id);
			attributes.put("connected_at", "2026-10-11T00:00:00Z");
			attributes.put("properties", Map.of("nickname", nickname));
			attributes.put("kakao_account", account);
			return attributes;
		}

	}

}
