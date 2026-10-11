package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import com.example.board.auth.oauth.OAuth2Properties.Registration;

/** TASK-4#1 제공자 등록: 설정된 제공자만 등록, redirect-uri 는 공개 주소 기준. */
class OAuth2ClientRegistrationsTest {

	static final List<OAuth2UserInfoMapper> MAPPERS = List.of(new GoogleUserInfoMapper(), new GitHubUserInfoMapper());

	static Registration clientOnly(String clientId) {
		return new Registration(clientId, "secret", null, null, null, null, null, null);
	}

	static OAuth2Properties properties(Map<String, Registration> registrations) {
		return new OAuth2Properties(null, null, null, registrations);
	}

	@Test
	@DisplayName("TASK-4#1 client-id 가 하나도 없으면 아무 제공자도 등록하지 않는다(앱은 그대로 뜬다)")
	void noneConfigured() {
		OAuth2ClientRegistrations registrations = OAuth2ClientRegistrations.from(
				properties(Map.of("google", clientOnly(""), "github", clientOnly(null))), MAPPERS,
				new PublicUrls("http://localhost:3000"));

		assertThat(registrations).isEmpty();
		assertThat(registrations.findByRegistrationId("google")).isNull();
	}

	@Test
	@DisplayName("TASK-4#1 설정된 제공자만 등록, redirect-uri = {공개 주소}/api/login/oauth2/code/{id}, 엔드포인트는 제공자 기본값")
	void onlyConfigured() {
		OAuth2ClientRegistrations registrations = OAuth2ClientRegistrations.from(
				properties(Map.of("github", clientOnly("gh-client"))), MAPPERS,
				new PublicUrls("https://d111.cloudfront.net/"));

		assertThat(registrations.findByRegistrationId("google")).isNull();
		ClientRegistration github = registrations.findByRegistrationId("github");
		assertThat(github.getClientId()).isEqualTo("gh-client");
		assertThat(github.getRedirectUri()).isEqualTo("https://d111.cloudfront.net/api/login/oauth2/code/github");
		assertThat(github.getProviderDetails().getAuthorizationUri())
			.isEqualTo("https://github.com/login/oauth/authorize");
		assertThat(github.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
	}

	@Test
	@DisplayName("TASK-4#1 Google 은 OIDC(openid scope, issuer·JWKS), 엔드포인트는 설정으로 덮을 수 있다")
	void googleIsOidcWithOverrides() {
		OAuth2ClientRegistrations registrations = OAuth2ClientRegistrations.from(
				properties(Map.of("google", new Registration("g-client", "g-secret", "http://stub/authorize",
						"http://stub/token", "http://stub/userinfo", "http://stub/jwks", "http://stub", null))),
				MAPPERS, new PublicUrls("http://localhost:3000"));

		ClientRegistration google = registrations.findByRegistrationId("google");
		assertThat(google.getScopes()).contains("openid", "email", "profile");
		assertThat(google.getRedirectUri()).isEqualTo("http://localhost:3000/api/login/oauth2/code/google");
		assertThat(google.getProviderDetails().getTokenUri()).isEqualTo("http://stub/token");
		assertThat(google.getProviderDetails().getJwkSetUri()).isEqualTo("http://stub/jwks");
		assertThat(google.getProviderDetails().getIssuerUri()).isEqualTo("http://stub");
	}

	@Test
	@DisplayName("TASK-4#1 공개 주소는 http(s) 절대 URL 이어야 한다(잘못된 설정은 기동 실패)")
	void publicBaseUrlMustBeAbsolute() {
		assertThatThrownBy(() -> new PublicUrls("localhost:3000")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new PublicUrls("")).isInstanceOf(IllegalArgumentException.class);
		assertThat(new PublicUrls("http://board.test/").url("/api/x")).isEqualTo("http://board.test/api/x");
	}

}
