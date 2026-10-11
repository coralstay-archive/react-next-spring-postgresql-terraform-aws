package com.example.board.auth.oauth;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.example.board.user.AuthProvider;

/**
 * GitHub (OAuth2, OIDC 아님). subject 는 {@code /user} 응답의 숫자 {@code id} 를 문자열로 (login 은 바뀔 수 있다).
 *
 * <p>email 은 사용자가 공개한 이메일만 오고 없으면 null 이다. 공개 이메일은 GitHub 에서 검증된 주소만 고를 수 있으므로
 * 검증됨으로 본다. 비공개 이메일까지 받으려면 {@code user:email} scope + {@code /user/emails}(primary·verified) 조회가 필요한데,
 * email 은 로그인 키가 아니라 표시용이라 하지 않는다.
 */
@Component
public class GitHubUserInfoMapper implements OAuth2UserInfoMapper {

	@Override
	public AuthProvider provider() {
		return AuthProvider.GITHUB;
	}

	@Override
	public ClientRegistration.Builder registration() {
		// scope read:user / userNameAttribute id
		return CommonOAuth2Provider.GITHUB.getBuilder(registrationId());
	}

	@Override
	public OAuth2UserInfo map(OAuth2User user) {
		Object id = user.getAttribute("id");
		String email = OAuth2UserInfoMapper.normalizeEmail(user.getAttribute("email"));
		Object name = user.getAttribute("name");
		Object login = user.getAttribute("login");
		String displayName = name instanceof String s && !s.isBlank() ? s : (login instanceof String l ? l : null);
		return new OAuth2UserInfo(provider(), id == null ? null : String.valueOf(id), email, email != null, displayName);
	}

}
