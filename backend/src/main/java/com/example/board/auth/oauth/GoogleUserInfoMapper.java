package com.example.board.auth.oauth;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.example.board.user.AuthProvider;

/** Google (OIDC). subject 는 {@code sub}. id_token 은 스프링이 JWKS 로 서명·iss·aud·nonce 를 검증한다. */
@Component
public class GoogleUserInfoMapper implements OAuth2UserInfoMapper {

	@Override
	public AuthProvider provider() {
		return AuthProvider.GOOGLE;
	}

	@Override
	public ClientRegistration.Builder registration() {
		// scope: openid, profile, email / issuer https://accounts.google.com / JWKS / userinfo / sub
		return CommonOAuth2Provider.GOOGLE.getBuilder(registrationId());
	}

	@Override
	public OAuth2UserInfo map(OAuth2User user) {
		OidcUser oidc = (OidcUser) user;
		String email = OAuth2UserInfoMapper.normalizeEmail(oidc.getEmail());
		boolean verified = email != null && Boolean.TRUE.equals(oidc.getEmailVerified());
		String name = oidc.getFullName();
		if (name == null || name.isBlank()) {
			name = oidc.getGivenName();
		}
		if ((name == null || name.isBlank()) && email != null) {
			name = email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
		}
		return new OAuth2UserInfo(provider(), oidc.getSubject(), email, verified, name);
	}

}
