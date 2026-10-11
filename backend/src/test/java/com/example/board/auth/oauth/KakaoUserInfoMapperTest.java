package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.example.board.user.AuthProvider;

/** TASK-5#2 Kakao: 숫자 id, 이메일·닉네임은 kakao_account(동의한 경우에만) 안에 있다. */
class KakaoUserInfoMapperTest {

	final KakaoUserInfoMapper mapper = new KakaoUserInfoMapper();

	static DefaultOAuth2User kakaoUser(Object id, Map<String, Object> account, Map<String, Object> properties) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("id", id);
		attributes.put("connected_at", "2026-10-11T00:00:00Z");
		if (properties != null) {
			attributes.put("properties", properties);
		}
		if (account != null) {
			attributes.put("kakao_account", account);
		}
		return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id");
	}

	static Map<String, Object> account(String email, Boolean valid, Boolean verified, String nickname) {
		Map<String, Object> account = new LinkedHashMap<>();
		if (nickname != null) {
			account.put("profile_nickname_needs_agreement", false);
			account.put("profile", Map.of("nickname", nickname, "is_default_nickname", false));
		}
		account.put("has_email", email != null);
		account.put("email_needs_agreement", email == null);
		if (email != null) {
			account.put("email", email);
		}
		if (valid != null) {
			account.put("is_email_valid", valid);
		}
		if (verified != null) {
			account.put("is_email_verified", verified);
		}
		return account;
	}

	@Test
	@DisplayName("TASK-5#2 Kakao 등록 기본값: kauth 인가·토큰, kapi /v2/user/me, client_secret_post, userNameAttribute=id, scope 는 쉼표 구분 한 덩어리")
	void registration() {
		ClientRegistration registration = mapper.registration()
			.clientId("kakao-rest-key")
			.clientSecret("kakao-secret")
			.redirectUri("http://board.test/api/login/oauth2/code/kakao")
			.build();

		assertThat(mapper.provider()).isEqualTo(AuthProvider.KAKAO);
		assertThat(registration.getRegistrationId()).isEqualTo("kakao");
		assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
			.isEqualTo("https://kauth.kakao.com/oauth/authorize");
		assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("https://kauth.kakao.com/oauth/token");
		assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
			.isEqualTo("https://kapi.kakao.com/v2/user/me");
		assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
		assertThat(registration.getScopes()).containsExactly("profile_nickname,account_email");
	}

	@Test
	@DisplayName("TASK-5#2 Kakao: subject=숫자 id 문자열, 유효·인증된 이메일, profile.nickname")
	void maps() {
		OAuth2UserInfo info = mapper.map(kakaoUser(3_456_789_012L, account("Bob@Kakao.com", true, true, "밥"), null));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.KAKAO, "3456789012", "bob@kakao.com", true, "밥"));
	}

	@Test
	@DisplayName("TASK-5#2 TASK-5#B1 TASK-5#3 Kakao: 이메일 미동의(kakao_account 에 email 없음)면 email null, 그래도 매핑된다")
	void noEmailConsent() {
		OAuth2UserInfo info = mapper.map(kakaoUser(42, account(null, null, null, "닉네임만"), null));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.KAKAO, "42", null, false, "닉네임만"));
	}

	@Test
	@DisplayName("TASK-5#2 TASK-5#3 Kakao: is_email_valid 나 is_email_verified 가 true 가 아니면 이메일을 버린다")
	void unverifiedEmailDropped() {
		assertThat(mapper.map(kakaoUser(1, account("a@kakao.com", true, false, "a"), null)).email()).isNull();
		assertThat(mapper.map(kakaoUser(2, account("b@kakao.com", false, true, "b"), null)).email()).isNull();
		assertThat(mapper.map(kakaoUser(3, account("c@kakao.com", null, null, "c"), null)).email()).isNull();
	}

	@Test
	@DisplayName("TASK-5#2 TASK-5#3 Kakao: profile.nickname 이 없으면 properties.nickname, 둘 다 없으면 null, kakao_account 자체가 없어도 된다")
	void nicknameFallbacks() {
		assertThat(mapper.map(kakaoUser(10, account(null, null, null, null), Map.of("nickname", "속성닉"))).displayName())
			.isEqualTo("속성닉");
		OAuth2UserInfo bare = mapper.map(kakaoUser(11, null, null));
		assertThat(bare).isEqualTo(new OAuth2UserInfo(AuthProvider.KAKAO, "11", null, false, null));
	}

	@Test
	@DisplayName("TASK-5#2 TASK-5#3 Kakao: id 가 문자열로 와도 그대로 subject, 정수형 int·long 모두 같은 문자열")
	void idTypes() {
		assertThat(mapper.map(kakaoUser("987", null, null)).subject()).isEqualTo("987");
		assertThat(mapper.map(kakaoUser(987, null, null)).subject()).isEqualTo("987");
		assertThat(mapper.map(kakaoUser(987L, null, null)).subject()).isEqualTo("987");
	}

	@Test
	@DisplayName("TASK-5#2 TASK-5#3 Kakao: id 가 없으면 매핑 실패")
	void missingId() {
		assertThatThrownBy(() -> mapper.map(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of("id", "", "kakao_account", Map.of()), "id")))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
