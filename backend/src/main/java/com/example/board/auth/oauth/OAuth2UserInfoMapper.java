package com.example.board.auth.oauth;

import java.util.Locale;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;

import com.example.board.user.AuthProvider;

/**
 * 제공자 하나를 붙이는 확장 지점 (doc-1 "새 로그인 수단 = enum + 매퍼 + 설정").
 *
 * <p>새 제공자(예: TASK-5 Naver·Kakao)는 이 인터페이스의 {@code @Component} 하나와 {@code app.oauth2.registrations.<id>.*}
 * 설정만 더하면 된다. 등록({@link OAuth2ClientRegistrations}), 로그인·연결 처리({@link OAuth2LoginSuccessHandler})는 매퍼
 * 목록을 보고 자동으로 따라온다.
 */
public interface OAuth2UserInfoMapper {

	AuthProvider provider();

	/** 등록 id. URL({@code /api/oauth2/authorization/{id}}, 콜백)과 설정 키에 쓴다. 기본은 provider 소문자. */
	default String registrationId() {
		return provider().name().toLowerCase(Locale.ROOT);
	}

	/**
	 * 제공자 기본값(엔드포인트, scope, 클라이언트 인증 방식, userNameAttribute)을 채운 빌더. client-id/secret 과 redirect-uri 는
	 * 등록 시 설정으로 채운다.
	 */
	ClientRegistration.Builder registration();

	/** 제공자 응답(OIDC 면 {@link org.springframework.security.oauth2.core.oidc.user.OidcUser})을 정규화한다. */
	OAuth2UserInfo map(OAuth2User user);

	/** 이메일 정규화: 앞뒤 공백 제거 + 소문자. 빈 값이나 320자 초과는 null. */
	static String normalizeEmail(Object value) {
		if (!(value instanceof String email) || email.isBlank()) {
			return null;
		}
		String normalized = email.strip().toLowerCase(Locale.ROOT);
		return normalized.length() > 320 ? null : normalized;
	}

}
