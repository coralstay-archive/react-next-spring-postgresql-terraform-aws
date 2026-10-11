package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.example.board.user.AuthProvider;

/** TASK-4#1 제공자별 사용자 정보 매퍼 (Google: OIDC sub, GitHub: 숫자 id). */
class OAuth2UserInfoMapperTest {

	static DefaultOidcUser oidcUser(Map<String, Object> claims) {
		Map<String, Object> idClaims = new LinkedHashMap<>(claims);
		OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), idClaims);
		return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")), idToken, new OidcUserInfo(claims));
	}

	@Test
	@DisplayName("TASK-4#1 Google: subject=sub, email·email_verified·name")
	void google() {
		OAuth2UserInfo info = new GoogleUserInfoMapper().map(oidcUser(
				Map.of("sub", "1081", "email", "Alice@Gmail.Test", "email_verified", true, "name", "Alice Kim")));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.GOOGLE, "1081", "alice@gmail.test", true, "Alice Kim"));
	}

	@Test
	@DisplayName("TASK-4#1 Google: 이름이 없으면 이메일 앞부분, email_verified 가 없으면 미검증")
	void googleFallbacks() {
		OAuth2UserInfo info = new GoogleUserInfoMapper().map(oidcUser(Map.of("sub", "1082", "email", "bob@x.test")));

		assertThat(info.emailVerified()).isFalse();
		assertThat(info.displayName()).isEqualTo("bob");
	}

	@Test
	@DisplayName("TASK-4#1 GitHub: subject=숫자 id 문자열, 이름이 없으면 login, 공개 이메일 없음(null) 허용")
	void github() {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("id", 583231);
		attributes.put("login", "octocat");
		attributes.put("name", null);
		attributes.put("email", null);

		OAuth2UserInfo info = new GitHubUserInfoMapper()
			.map(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id"));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.GITHUB, "583231", null, false, "octocat"));
	}

	@Test
	@DisplayName("TASK-4#1 GitHub: 공개 이메일은 GitHub 가 검증한 주소라 검증됨으로 본다")
	void githubPublicEmail() {
		OAuth2UserInfo info = new GitHubUserInfoMapper().map(new DefaultOAuth2User(
				List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of("id", 9L, "login", "mona", "name", "Mona Lisa", "email", "Mona@GitHub.Test"), "id"));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.GITHUB, "9", "mona@github.test", true, "Mona Lisa"));
	}

}
