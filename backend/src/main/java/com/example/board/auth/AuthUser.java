package com.example.board.auth;

import java.io.Serializable;
import java.util.Objects;

import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserStatus;

/**
 * 애플리케이션 수준 인증 주체. 세션 경로와 Bearer(JWT) 경로 모두 principal 이 이 타입이다.
 * 세션에 직렬화되어 저장되므로 {@link Serializable} 이다.
 *
 * <p>role/status 는 인증 시점의 값이다. SUSPENDED 판정처럼 최신 값이 필요한 곳은 DB 를 다시 본다.
 */
public record AuthUser(Long id, Role role, UserStatus status) implements Serializable {

	public AuthUser {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(status, "status");
	}

	public static AuthUser from(User user) {
		return new AuthUser(user.getId(), user.getRole(), user.getStatus());
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	public boolean isSuspended() {
		return status == UserStatus.SUSPENDED;
	}

}
