package com.example.board.auth;

import java.util.List;

import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserStatus;

/** 가입·로그인·me 응답의 사용자 요약. providers 는 계정에 연결된 로그인 수단 목록(정렬, 중복 없음). */
public record UserSummary(Long id, String nickname, String email, Role role, UserStatus status,
		List<AuthProvider> providers) {

	public static UserSummary of(User user, List<AuthProvider> providers) {
		return new UserSummary(user.getId(), user.getNickname(), user.getEmail(), user.getRole(), user.getStatus(),
				providers.stream().distinct().sorted().toList());
	}

}
