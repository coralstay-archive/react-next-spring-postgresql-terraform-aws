package com.example.board.auth.oauth;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.board.auth.oauth.OAuth2Properties.Registration;
import com.example.board.user.AuthProvider;

/**
 * Cognito User Pool (OIDC, decision-6). Terraform {@code infra/main/modules/cognito} 의 출력으로 설정한다.
 *
 * <ul>
 * <li>엔드포인트: issuer 디스커버리({@code .well-known/openid-configuration})를 쓰지 않고 설정에서 바로 만든다. 그래서 Cognito 에
 * 닿지 않아도 앱이 뜬다. Hosted UI 도메인({@code COGNITO_DOMAIN}) → {@code /oauth2/authorize}, {@code /oauth2/token},
 * {@code /oauth2/userInfo}. issuer({@code COGNITO_ISSUER_URI} = {@code https://cognito-idp.<region>.amazonaws.com/<pool id>})
 * → JWKS {@code <issuer>/.well-known/jwks.json}. 스프링이 id_token 의 서명·iss·aud·nonce 를 검증한다.</li>
 * <li>클라이언트 인증: App Client secret 을 Basic 헤더로({@code client_secret_basic}).</li>
 * <li>subject: {@code sub} (풀 안에서 영구, 이메일이 바뀌어도 같다).</li>
 * <li>email_verified: id_token 은 boolean, userinfo 는 문자열 {@code "true"} 로 주기도 해서 둘 다 받는다.</li>
 * </ul>
 */
@Component
public class CognitoUserInfoMapper implements OAuth2UserInfoMapper {

	@Override
	public AuthProvider provider() {
		return AuthProvider.COGNITO;
	}

	/** 엔드포인트 없는 기본값. 실제 등록은 {@link #registration(Registration)} 이 도메인·issuer 로 채운다. */
	@Override
	public ClientRegistration.Builder registration() {
		return ClientRegistration.withRegistrationId(registrationId())
			.clientName("Cognito")
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.scope("openid", "email", "profile")
			.userNameAttributeName(IdTokenClaimNames.SUB);
	}

	@Override
	public ClientRegistration.Builder registration(Registration config) {
		String issuer = trimSlash(config == null ? null : config.issuerUri());
		String domain = trimSlash(config == null ? null : config.domain());
		if (issuer == null) {
			throw new IllegalArgumentException(
					"Cognito: COGNITO_CLIENT_ID 를 주면 COGNITO_ISSUER_URI(https://cognito-idp.<region>.amazonaws.com/<pool id>)도 필요합니다");
		}
		if (domain == null) {
			throw new IllegalArgumentException(
					"Cognito: COGNITO_CLIENT_ID 를 주면 COGNITO_DOMAIN(Hosted UI 주소 https://<prefix>.auth.<region>.amazoncognito.com)도 필요합니다");
		}
		if (!domain.contains("://")) {
			domain = "https://" + domain;
		}
		return registration().issuerUri(issuer)
			.jwkSetUri(issuer + "/.well-known/jwks.json")
			.authorizationUri(domain + "/oauth2/authorize")
			.tokenUri(domain + "/oauth2/token")
			.userInfoUri(domain + "/oauth2/userInfo");
	}

	@Override
	public OAuth2UserInfo map(OAuth2User user) {
		OidcUser oidc = (OidcUser) user;
		Map<String, Object> claims = oidc.getClaims();
		String email = OAuth2UserInfoMapper.normalizeEmail(claims.get(StandardClaimNames.EMAIL));
		boolean verified = email != null && isTrue(claims.get(StandardClaimNames.EMAIL_VERIFIED));
		String name = text(claims.get(StandardClaimNames.NAME));
		if (name == null) {
			name = text(claims.get(StandardClaimNames.PREFERRED_USERNAME));
		}
		if (name == null) {
			name = text(claims.get(StandardClaimNames.GIVEN_NAME));
		}
		if (name == null && email != null) {
			name = email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
		}
		return new OAuth2UserInfo(provider(), oidc.getSubject(), email, verified, name);
	}

	/** Cognito userinfo 는 {@code "email_verified": "true"} 처럼 문자열로 줄 때가 있다. */
	static boolean isTrue(Object value) {
		return Boolean.TRUE.equals(value) || (value instanceof String s && "true".equalsIgnoreCase(s.strip()));
	}

	private static String text(Object value) {
		return value instanceof String s && !s.isBlank() ? s.strip() : null;
	}

	private static String trimSlash(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.strip();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed.isEmpty() ? null : trimmed;
	}

}
