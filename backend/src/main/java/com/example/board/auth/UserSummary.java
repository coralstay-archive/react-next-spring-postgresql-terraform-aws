package com.example.board.auth;

import java.util.List;

import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserStatus;

/**
 * 가입·로그인·me 응답의 사용자 요약. providers 는 계정에 연결된 로그인 수단 목록(정렬, 중복 없음).
 *
 * <p>TASK-7 B4: {@code stepUpRequired} 면 이 세션·토큰은 제한 상태이고, {@code stepUpProviders} 는 다시 인증할 때 쓸 수 있는
 * 수단(PHONE 을 뺀 연결된 수단)이다. 제한이 없으면 빈 목록이다.
 */
public record UserSummary(Long id, String nickname, String email, Role role, UserStatus status,
		List<AuthProvider> providers, boolean stepUpRequired, List<AuthProvider> stepUpProviders) {

	public static UserSummary of(User user, List<AuthProvider> providers) {
		return of(user, providers, false);
	}

	public static UserSummary of(User user, List<AuthProvider> providers, boolean stepUpRequired) {
		List<AuthProvider> sorted = providers.stream().distinct().sorted().toList();
		List<AuthProvider> stepUp = stepUpRequired
				? sorted.stream().filter(provider -> provider != AuthProvider.PHONE).toList() : List.of();
		return new UserSummary(user.getId(), user.getNickname(), user.getEmail(), user.getRole(), user.getStatus(),
				sorted, stepUpRequired, stepUp);
	}

}
