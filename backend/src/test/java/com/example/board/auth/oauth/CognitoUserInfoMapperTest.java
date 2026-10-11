package com.example.board.auth.oauth;

import static com.example.board.auth.oauth.OAuth2UserInfoMapperTest.oidcUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import com.example.board.auth.oauth.OAuth2Properties.Registration;
import com.example.board.user.AuthProvider;

/**
 * TASK-6#2 Cognito(OIDC): 엔드포인트는 issuer 디스커버리 없이 Hosted UI 도메인과 issuer 에서 만든다(앱은 Cognito 없이도 뜬다).
 * Cognito userinfo 는 {@code email_verified} 를 문자열 {@code "true"} 로 주기도 한다.
 */
class CognitoUserInfoMapperTest {

	static final String ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_AbCdEf";

	static final String DOMAIN = "https://board-login.auth.us-east-1.amazoncognito.com";

	final CognitoUserInfoMapper mapper = new CognitoUserInfoMapper();

	static Registration config(String issuer, String domain) {
		return new Registration("cognito-client", "cognito-secret", null, null, null, null, issuer, domain);
	}

	static ClientRegistration build(ClientRegistration.Builder builder) {
		return builder.clientId("cognito-client")
			.clientSecret("cognito-secret")
			.redirectUri("https://board.test/api/login/oauth2/code/cognito")
			.build();
	}

	static Map<String, Object> claims(Object... keyValues) {
		Map<String, Object> claims = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			claims.put((String) keyValues[i], keyValues[i + 1]);
		}
		return claims;
	}

	@Test
	@DisplayName("TASK-6#2 Cognito 등록: provider COGNITO, id cognito, OIDC(openid email profile), code flow, client_secret_basic, userNameAttribute=sub")
	void registrationDefaults() {
		ClientRegistration registration = build(mapper.registration(config(ISSUER, DOMAIN)));

		assertThat(mapper.provider()).isEqualTo(AuthProvider.COGNITO);
		assertThat(mapper.registrationId()).isEqualTo("cognito");
		assertThat(registration.getRegistrationId()).isEqualTo("cognito");
		assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "email", "profile");
		assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
		assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("sub");
	}

	@Test
	@DisplayName("TASK-6#2 Cognito 엔드포인트: 도메인/oauth2/authorize·token·userInfo, JWKS 는 issuer/.well-known/jwks.json (디스커버리 없음)")
	void endpointsFromDomainAndIssuer() {
		ClientRegistration registration = build(mapper.registration(config(ISSUER, DOMAIN)));

		ClientRegistration.ProviderDetails details = registration.getProviderDetails();
		assertThat(details.getAuthorizationUri()).isEqualTo(DOMAIN + "/oauth2/authorize");
		assertThat(details.getTokenUri()).isEqualTo(DOMAIN + "/oauth2/token");
		assertThat(details.getUserInfoEndpoint().getUri()).isEqualTo(DOMAIN + "/oauth2/userInfo");
		assertThat(details.getIssuerUri()).isEqualTo(ISSUER);
		assertThat(details.getJwkSetUri()).isEqualTo(ISSUER + "/.well-known/jwks.json");
	}

	@Test
	@DisplayName("TASK-6#2 Cognito 도메인은 scheme 없이(prefix.auth.region.amazoncognito.com) 줘도 https 로, 끝 / 는 뗀다")
	void domainNormalized() {
		ClientRegistration registration = build(mapper.registration(
				config(ISSUER + "/", "board-login.auth.us-east-1.amazoncognito.com/")));

		assertThat(registration.getProviderDetails().getAuthorizationUri()).isEqualTo(DOMAIN + "/oauth2/authorize");
		assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo(ISSUER);
		assertThat(registration.getProviderDetails().getJwkSetUri()).isEqualTo(ISSUER + "/.well-known/jwks.json");
	}

	@Test
	@DisplayName("TASK-6#2 client-id 가 있는데 issuer 나 도메인이 없으면 잘못된 설정(기동 실패, iss 검증 없이 뜨지 않는다)")
	void issuerAndDomainRequired() {
		assertThatThrownBy(() -> mapper.registration(config(null, DOMAIN))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("COGNITO_ISSUER_URI");
		assertThatThrownBy(() -> mapper.registration(config(ISSUER, " "))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("COGNITO_DOMAIN");
	}

	@Test
	@DisplayName("TASK-6#2 Cognito 가 설정되지 않으면(client-id 없음) 등록하지 않는다 — 앱은 Cognito 없이 오프라인으로 뜬다")
	void notConfiguredNotRegistered() {
		OAuth2ClientRegistrations registrations = OAuth2ClientRegistrations.from(
				new OAuth2Properties(null, null, null,
						Map.of("cognito", new Registration("", "", null, null, null, null, null, null))),
				List.of(mapper), new PublicUrls("http://localhost:3000"));

		assertThat(registrations.findByRegistrationId("cognito")).isNull();
	}

	@Test
	@DisplayName("TASK-6#2 설정되면 OAuth2ClientRegistrations 가 도메인·issuer 로 만든 Cognito 등록 + 공개 주소 redirect-uri 를 둔다")
	void registeredFromConfig() {
		OAuth2ClientRegistrations registrations = OAuth2ClientRegistrations.from(
				new OAuth2Properties(null, null, null, Map.of("cognito", config(ISSUER, DOMAIN))), List.of(mapper),
				new PublicUrls("https://d111.cloudfront.net"));

		ClientRegistration cognito = registrations.findByRegistrationId("cognito");
		assertThat(cognito.getClientId()).isEqualTo("cognito-client");
		assertThat(cognito.getRedirectUri()).isEqualTo("https://d111.cloudfront.net/api/login/oauth2/code/cognito");
		assertThat(cognito.getProviderDetails().getTokenUri()).isEqualTo(DOMAIN + "/oauth2/token");
		assertThat(cognito.getProviderDetails().getIssuerUri()).isEqualTo(ISSUER);
	}

	@Test
	@DisplayName("TASK-6#2 Cognito: subject=sub, email 소문자, email_verified(boolean) true 면 검증됨, 이름은 name")
	void mapsBooleanVerified() {
		OAuth2UserInfo info = mapper.map(oidcUser(claims("sub", "6f1c-uuid", "email", "Alice@Example.COM",
				"email_verified", true, "name", "Alice Kim", "cognito:username", "6f1c-uuid")));

		assertThat(info).isEqualTo(
				new OAuth2UserInfo(AuthProvider.COGNITO, "6f1c-uuid", "alice@example.com", true, "Alice Kim"));
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 Cognito userinfo 의 email_verified 문자열 \"true\" 도 검증됨으로 본다")
	void mapsStringVerified() {
		OAuth2UserInfo info = mapper.map(oidcUser(claims("sub", "s-1", "email", "bob@example.com", "email_verified",
				"true")));

		assertThat(info.emailVerified()).isTrue();
		assertThat(info.email()).isEqualTo("bob@example.com");
	}

	@Test
	@DisplayName("TASK-6#3 TASK-6#2 email_verified 가 \"false\"·false·없음이면 미검증(이메일은 identity 에만 기록)")
	void unverified() {
		assertThat(mapper.map(oidcUser(claims("sub", "s-2", "email", "c@example.com", "email_verified", "false")))
			.emailVerified()).isFalse();
		assertThat(mapper.map(oidcUser(claims("sub", "s-3", "email", "c@example.com", "email_verified", false)))
			.emailVerified()).isFalse();
		OAuth2UserInfo missing = mapper.map(oidcUser(claims("sub", "s-4", "email", "c@example.com")));
		assertThat(missing.emailVerified()).isFalse();
		assertThat(missing.email()).isEqualTo("c@example.com");
	}

	@Test
	@DisplayName("TASK-6#2 이름 후보: name → preferred_username → given_name → 이메일 앞부분, 없으면 null")
	void displayNameFallbacks() {
		assertThat(mapper.map(oidcUser(claims("sub", "a", "preferred_username", "alice", "given_name", "Al")))
			.displayName()).isEqualTo("alice");
		assertThat(mapper.map(oidcUser(claims("sub", "b", "given_name", "Al"))).displayName()).isEqualTo("Al");
		assertThat(mapper.map(oidcUser(claims("sub", "c", "email", "carol@example.com"))).displayName())
			.isEqualTo("carol");
		assertThat(mapper.map(oidcUser(claims("sub", "d"))).displayName()).isNull();
	}

}
