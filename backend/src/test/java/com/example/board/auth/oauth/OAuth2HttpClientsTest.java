package com.example.board.auth.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.jwt.JwtException;

import com.example.board.support.OAuthProviderStub;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * TASK-25#3 BE-21 OAuth 제공자 HTTP 호출(토큰·userinfo·JWKS)의 연결·읽기 타임아웃. 설정({@code app.oauth2.http.*})의 기본값·검증과,
 * 응답을 늦게 주는 로컬 서버(WireMock 지연)에 대해 세 클라이언트가 읽기 타임아웃 안에 실패하는지 본다.
 */
class OAuth2HttpClientsTest {

	static final Duration READ_TIMEOUT = Duration.ofMillis(500);

	static final int DELAY_MILLIS = 5_000;

	WireMockServer server;

	OAuth2HttpClients clients;

	ClientRegistration registration;

	@BeforeEach
	void setUp() {
		server = new WireMockServer(options().dynamicPort());
		server.start();
		clients = new OAuth2HttpClients(new OAuth2HttpProperties(Duration.ofSeconds(1), READ_TIMEOUT));
		registration = ClientRegistration.withRegistrationId("slow")
			.clientId("client")
			.clientSecret("secret")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("http://board.test/api/login/oauth2/code/slow")
			.scope("openid")
			.authorizationUri(server.baseUrl() + "/authorize")
			.tokenUri(server.baseUrl() + "/token")
			.userInfoUri(server.baseUrl() + "/userinfo")
			.userNameAttributeName("sub")
			.jwkSetUri(server.baseUrl() + "/jwks")
			.issuerUri(server.baseUrl())
			.build();
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	private static Duration since(Instant start) {
		return Duration.between(start, Instant.now());
	}

	@Test
	@DisplayName("TASK-25#3 기본값: 연결 3초, 읽기 5초")
	void defaults() {
		OAuth2HttpProperties properties = new OAuth2HttpProperties(null, null);

		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
		assertThat(new OAuth2HttpClients(properties).httpClient().connectTimeout()).contains(Duration.ofSeconds(3));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(OAuth2HttpProperties.class)
	static class PropertiesConfig {

	}

	@Test
	@DisplayName("TASK-25#3 env 로 바꿀 수 있고(app.oauth2.http.*), 0·음수면 기동하지 않는다")
	void binding() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);
		runner.withPropertyValues("app.oauth2.http.connect-timeout=2s", "app.oauth2.http.read-timeout=7s")
			.run(ctx -> assertThat(ctx.getBean(OAuth2HttpProperties.class))
				.isEqualTo(new OAuth2HttpProperties(Duration.ofSeconds(2), Duration.ofSeconds(7))));
		runner.withPropertyValues("app.oauth2.http.read-timeout=0s").run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("app.oauth2.http.connect-timeout=-1s").run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	@DisplayName("TASK-25#3 토큰 엔드포인트가 응답하지 않으면 읽기 타임아웃 안에 invalid_token_response")
	void tokenTimeout() {
		server.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withFixedDelay(DELAY_MILLIS)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"access_token\":\"at\",\"token_type\":\"bearer\"}")));
		OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
			.authorizationUri(registration.getProviderDetails().getAuthorizationUri())
			.clientId("client")
			.redirectUri(registration.getRedirectUri())
			.state("state")
			.build();
		OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("code")
			.redirectUri(registration.getRedirectUri())
			.state("state")
			.build();
		Instant start = Instant.now();

		assertThatThrownBy(() -> clients.tokenResponseClient()
			.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
					new OAuth2AuthorizationExchange(request, response))))
			.isInstanceOf(OAuth2AuthorizationException.class)
			.satisfies(ex -> assertThat(((OAuth2AuthorizationException) ex).getError().getErrorCode())
				.isEqualTo("invalid_token_response"));
		assertThat(since(start)).isLessThan(Duration.ofMillis(DELAY_MILLIS - 1_000));
	}

	@Test
	@DisplayName("TASK-25#3 userinfo 가 응답하지 않으면 읽기 타임아웃 안에 invalid_user_info_response")
	void userInfoTimeout() {
		server.stubFor(get(urlPathEqualTo("/userinfo"))
			.willReturn(aResponse().withFixedDelay(DELAY_MILLIS).withHeader("Content-Type", "application/json").withBody("{\"sub\":\"1\"}")));
		OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", Instant.now(),
				Instant.now().plusSeconds(60));
		Instant start = Instant.now();

		assertThatThrownBy(() -> clients.userService().loadUser(new OAuth2UserRequest(registration, token)))
			.isInstanceOf(OAuth2AuthenticationException.class)
			.satisfies(ex -> assertThat(((OAuth2AuthenticationException) ex).getError().getErrorCode())
				.isEqualTo("invalid_user_info_response"));
		assertThat(since(start)).isLessThan(Duration.ofMillis(DELAY_MILLIS - 1_000));
	}

	@Test
	@DisplayName("TASK-25#3 JWKS 가 응답하지 않으면 id_token 검증이 읽기 타임아웃 안에 실패한다")
	void jwksTimeout() throws Exception {
		RSAKey key = OAuthProviderStub.newKey("k1");
		server.stubFor(get(urlPathEqualTo("/jwks")).willReturn(aResponse().withFixedDelay(DELAY_MILLIS)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")));
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
				new JWTClaimsSet.Builder().issuer(server.baseUrl())
					.subject("1")
					.audience("client")
					.issueTime(new Date())
					.expirationTime(Date.from(Instant.now().plusSeconds(60)))
					.build());
		jwt.sign(new RSASSASigner(key));
		Instant start = Instant.now();

		assertThatThrownBy(() -> clients.idTokenDecoderFactory().createDecoder(registration).decode(jwt.serialize()))
			.isInstanceOf(JwtException.class);
		assertThat(since(start)).isLessThan(Duration.ofMillis(DELAY_MILLIS - 1_000));
	}

	@Test
	@DisplayName("TASK-25#3 JWKS 가 제때 응답하면 기본 OIDC 검증(서명·iss·aud·exp)대로 디코딩한다")
	void jwksOk() throws Exception {
		RSAKey key = OAuthProviderStub.newKey("k1");
		server.stubFor(get(urlPathEqualTo("/jwks")).willReturn(aResponse().withHeader("Content-Type", "application/json")
			.withBody("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")));
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
				new JWTClaimsSet.Builder().issuer(server.baseUrl())
					.subject("1")
					.audience("client")
					.issueTime(new Date())
					.expirationTime(Date.from(Instant.now().plusSeconds(60)))
					.build());
		jwt.sign(new RSASSASigner(key));
		SignedJWT otherAudience = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
				new JWTClaimsSet.Builder().issuer(server.baseUrl())
					.subject("1")
					.audience("someone-else")
					.issueTime(new Date())
					.expirationTime(Date.from(Instant.now().plusSeconds(60)))
					.build());
		otherAudience.sign(new RSASSASigner(key));

		assertThat(clients.idTokenDecoderFactory().createDecoder(registration).decode(jwt.serialize()).getSubject())
			.isEqualTo("1");
		assertThatThrownBy(
				() -> clients.idTokenDecoderFactory().createDecoder(registration).decode(otherAudience.serialize()))
			.isInstanceOf(JwtException.class);
	}

}
