package com.example.board.auth.oauth;

import java.util.Map;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.example.board.user.AuthProvider;

/**
 * Kakao 로그인 (OAuth2; Kakao 의 OIDC 는 쓰지 않는다). 사용자 정보({@code /v2/user/me}):
 * {@code {"id":123,"properties":{"nickname":..},"kakao_account":{"profile":{"nickname":..},"email":..,"is_email_valid":..,"is_email_verified":..}}}.
 *
 * <ul>
 * <li>subject: 숫자 {@code id} 를 문자열로.</li>
 * <li>email: 선택 동의 항목이라 없을 수 있다(B1: 그래도 계정을 만든다, email null). {@code is_email_valid} 와
 * {@code is_email_verified} 가 모두 true 일 때만 쓰고 그때는 검증됨. 아니면 identity 에도 기록하지 않는다.</li>
 * <li>displayName: {@code kakao_account.profile.nickname} → {@code properties.nickname}.</li>
 * <li>클라이언트 인증: 토큰 엔드포인트가 form 파라미터 {@code client_secret} 을 받으므로 {@code client_secret_post}. 콘솔의
 * "Client Secret" 을 켜 두는 것을 전제로 한다(기본 켜짐).</li>
 * <li>scope: Kakao 는 동의 항목 ID 를 <b>쉼표</b>로 구분한다. 스프링은 scope 를 공백으로 이어 붙이므로 쉼표로 이은 한 덩어리를 둔다.
 * 콘솔 동의 항목에 닉네임(필수 또는 선택)과 카카오계정 이메일(선택)이 설정돼 있어야 한다(없으면 KOE205).</li>
 * </ul>
 */
@Component
public class KakaoUserInfoMapper implements OAuth2UserInfoMapper {

	static final String SCOPE = "profile_nickname,account_email";

	@Override
	public AuthProvider provider() {
		return AuthProvider.KAKAO;
	}

	@Override
	public ClientRegistration.Builder registration() {
		return ClientRegistration.withRegistrationId(registrationId())
			.clientName("Kakao")
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.scope(SCOPE)
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.tokenUri("https://kauth.kakao.com/oauth/token")
			.userInfoUri("https://kapi.kakao.com/v2/user/me")
			.userNameAttributeName("id");
	}

	@Override
	public OAuth2UserInfo map(OAuth2User user) {
		String id = id(user.getAttribute("id"));
		if (id == null) {
			throw new IllegalArgumentException("Kakao 응답에 id 가 없습니다");
		}
		Map<?, ?> account = object(user.getAttribute("kakao_account"));
		String email = null;
		if (Boolean.TRUE.equals(account.get("is_email_valid")) && Boolean.TRUE.equals(account.get("is_email_verified"))) {
			email = OAuth2UserInfoMapper.normalizeEmail(account.get("email"));
		}
		String nickname = text(object(account.get("profile")).get("nickname"));
		if (nickname == null) {
			nickname = text(object(user.getAttribute("properties")).get("nickname"));
		}
		return new OAuth2UserInfo(provider(), id, email, email != null, nickname);
	}

	private static String id(Object value) {
		if (value instanceof Number n) {
			return Long.toString(n.longValue());
		}
		return text(value);
	}

	private static Map<?, ?> object(Object value) {
		return value instanceof Map<?, ?> m ? m : Map.of();
	}

	private static String text(Object value) {
		return value instanceof String s && !s.isBlank() ? s.strip() : null;
	}

}
