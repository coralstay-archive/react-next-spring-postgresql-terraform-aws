package com.example.board.auth.oauth;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.example.board.user.AuthProvider;

/**
 * Naver 로그인 (OAuth2, OIDC 아님). 프로필 API({@code /v1/nid/me}) 응답은 사용자 정보를 한 번 감싼다:
 * {@code {"resultcode":"00","message":"success","response":{"id":..,"email":..,"nickname":..,"name":..}}}. 그래서
 * userNameAttribute 는 {@code response} 이고(스프링은 이 키가 있는지만 본다) 값은 그 안에서 꺼낸다.
 *
 * <ul>
 * <li>subject: {@code response.id} (앱별 고유 문자열).</li>
 * <li>email: Naver 는 검증 여부 필드를 주지 않는다. 오는 값은 네이버 계정의 기본 이메일(아이디@naver.com) 또는 사용자가 인증을 거쳐
 * 바꾼 연락처 이메일이므로 <b>검증됨</b>으로 본다. 동의하지 않으면 오지 않는다(null). email 은 로그인·병합 키가 아니고(decision-8)
 * {@code users.email} 표시·연락용으로만 쓴다.</li>
 * <li>displayName: {@code nickname} → {@code name}. 둘 다 없으면 null ({@code Nicknames} 가 대체 닉네임을 만든다).</li>
 * <li>클라이언트 인증: 토큰 엔드포인트가 client_id·client_secret 을 요청 파라미터로 받으므로 {@code client_secret_post}.</li>
 * <li>scope 는 보내지 않는다. 받을 항목(이메일, 별명, 이름)은 Naver Developers 콘솔의 "제공 정보 선택"으로 정한다.</li>
 * </ul>
 */
@Component
public class NaverUserInfoMapper implements OAuth2UserInfoMapper {

	@Override
	public AuthProvider provider() {
		return AuthProvider.NAVER;
	}

	@Override
	public ClientRegistration.Builder registration() {
		return ClientRegistration.withRegistrationId(registrationId())
			.clientName("Naver")
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.authorizationUri("https://nid.naver.com/oauth2.0/authorize")
			.tokenUri("https://nid.naver.com/oauth2.0/token")
			.userInfoUri("https://openapi.naver.com/v1/nid/me")
			.userNameAttributeName("response");
	}

	@Override
	public OAuth2UserInfo map(OAuth2User user) {
		if (!(user.getAttribute("response") instanceof Map<?, ?> response)) {
			throw new IllegalArgumentException("Naver 응답에 response 가 없습니다");
		}
		String id = text(response.get("id"));
		if (id == null) {
			throw new IllegalArgumentException("Naver 응답에 response.id 가 없습니다");
		}
		String email = OAuth2UserInfoMapper.normalizeEmail(response.get("email"));
		String nickname = text(response.get("nickname"));
		String displayName = nickname != null ? nickname : text(response.get("name"));
		return new OAuth2UserInfo(provider(), id, email, email != null, displayName);
	}

	private static String text(Object value) {
		if (value instanceof String s && !s.isBlank()) {
			return s;
		}
		return value instanceof Number n ? n.toString() : null;
	}

}
