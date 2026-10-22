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
 * <p>세션·토큰에 저장된 role/status 는 인증 시점의 값이다. 요청을 처리하는 동안의 principal 은
 * {@code CurrentUserFilter} 가 DB 최신 role·status 로 바꾼 것이다(TASK-24#2).
 *
 * <p>{@code stepUpRequired} (TASK-7 B4): 다른 로그인 수단도 있는 계정에 PHONE 으로 로그인한 제한 세션·토큰. 기존 수단으로 다시
 * 인증하기 전까지 읽기만 된다({@code StepUpRequiredWriteFilter}). 레코드 직렬화는 이름으로 맞추므로, 이 필드가 생기기 전에 저장된
 * 세션도 false 로 읽힌다.
 */
public record AuthUser(Long id, Role role, UserStatus status, boolean stepUpRequired) implements Serializable {

	public AuthUser {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(status, "status");
	}

	public AuthUser(Long id, Role role, UserStatus status) {
		this(id, role, status, false);
	}

	public static AuthUser from(User user) {
		return from(user, false);
	}

	public static AuthUser from(User user, boolean stepUpRequired) {
		return new AuthUser(user.getId(), user.getRole(), user.getStatus(), stepUpRequired);
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	public boolean isSuspended() {
		return status == UserStatus.SUSPENDED;
	}

}
