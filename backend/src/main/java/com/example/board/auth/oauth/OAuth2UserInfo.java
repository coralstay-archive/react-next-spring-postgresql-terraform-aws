package com.example.board.auth.oauth;

import java.util.Objects;

import com.example.board.user.AuthProvider;

/**
 * 제공자 응답을 정규화한 사용자 정보. {@link com.example.board.auth.identity.IdentityResolver} 의 입력이다.
 *
 * @param provider 로그인 수단
 * @param subject 제공자 안에서 사용자를 영구히 가리키는 값 (Google {@code sub}, GitHub 숫자 {@code id})
 * @param email 소문자 이메일 또는 null. identity 에 기록만 하고 로그인·병합 키로 쓰지 않는다(decision-8)
 * @param emailVerified 제공자가 이메일 소유를 확인했는지. 새 계정의 {@code users.email} 은 검증된 경우에만 채운다
 * @param displayName 닉네임 후보 (이름, 로그인 아이디 등). null 가능
 */
public record OAuth2UserInfo(AuthProvider provider, String subject, String email, boolean emailVerified,
		String displayName) {

	public OAuth2UserInfo {
		Objects.requireNonNull(provider, "provider");
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject 가 비어 있습니다");
		}
	}

}
