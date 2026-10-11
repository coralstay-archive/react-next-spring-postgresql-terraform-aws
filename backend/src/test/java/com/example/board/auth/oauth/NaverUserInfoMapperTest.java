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

/** TASK-5#1 Naver: 프로필 응답은 {@code {"resultcode","message","response":{...}}} 로 한 번 감싸여 있다. */
class NaverUserInfoMapperTest {

	final NaverUserInfoMapper mapper = new NaverUserInfoMapper();

	static DefaultOAuth2User naverUser(Map<String, Object> response) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("resultcode", "00");
		attributes.put("message", "success");
		attributes.put("response", response);
		return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "response");
	}

	@Test
	@DisplayName("TASK-5#1 Naver 등록 기본값: nid.naver.com 인가·토큰, openapi 프로필, authorization_code, client_secret_post, userNameAttribute=response")
	void registration() {
		ClientRegistration registration = mapper.registration()
			.clientId("naver-client")
			.clientSecret("naver-secret")
			.redirectUri("http://board.test/api/login/oauth2/code/naver")
			.build();

		assertThat(mapper.provider()).isEqualTo(AuthProvider.NAVER);
		assertThat(registration.getRegistrationId()).isEqualTo("naver");
		assertThat(registration.getAuthorizationGrantType()).isEqualTo(AuthorizationGrantType.AUTHORIZATION_CODE);
		assertThat(registration.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
		assertThat(registration.getProviderDetails().getAuthorizationUri())
			.isEqualTo("https://nid.naver.com/oauth2.0/authorize");
		assertThat(registration.getProviderDetails().getTokenUri()).isEqualTo("https://nid.naver.com/oauth2.0/token");
		assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUri())
			.isEqualTo("https://openapi.naver.com/v1/nid/me");
		assertThat(registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
			.isEqualTo("response");
		assertThat(registration.getScopes()).as("Naver 는 동의 항목을 콘솔에서 정한다(scope 없음)").isEmpty();
	}

	@Test
	@DisplayName("TASK-5#1 Naver: subject=response.id, email 소문자(검증됨으로 본다), 닉네임 우선")
	void maps() {
		OAuth2UserInfo info = mapper.map(naverUser(Map.of("id", "32742776", "email", "Alice@Naver.com", "nickname",
				"앨리스", "name", "김앨리스")));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.NAVER, "32742776", "alice@naver.com", true, "앨리스"));
	}

	@Test
	@DisplayName("TASK-5#1 TASK-5#3 Naver: 닉네임이 없으면 name, 이메일 미동의면 email null·미검증")
	void fallbacks() {
		OAuth2UserInfo info = mapper.map(naverUser(Map.of("id", "a1", "name", "홍길동")));

		assertThat(info).isEqualTo(new OAuth2UserInfo(AuthProvider.NAVER, "a1", null, false, "홍길동"));
	}

	@Test
	@DisplayName("TASK-5#1 TASK-5#3 Naver: 닉네임·이름이 모두 없거나 공백이면 displayName null (Nicknames 가 userNNNN 으로 채운다)")
	void noNames() {
		OAuth2UserInfo info = mapper.map(naverUser(Map.of("id", "a2", "nickname", "  ")));

		assertThat(info.displayName()).isNull();
	}

	@Test
	@DisplayName("TASK-5#1 TASK-5#3 Naver: response 나 response.id 가 없으면 매핑 실패(예외 → 성공 핸들러가 invalid_user_info)")
	void missingId() {
		assertThatThrownBy(() -> mapper.map(naverUser(Map.of("nickname", "no-id"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mapper.map(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
				Map.of("response", "not-a-map"), "response")))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
