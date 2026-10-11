package com.example.board.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.example.board.auth.AuthUser;

/**
 * 소유권 인가 (TASK-2 B4): 수정·삭제는 본인 또는 ADMIN 만. {@code @PreAuthorize} 에서 빈 이름 {@code ownership} 으로 쓴다.
 *
 * <pre>
 * &#64;PreAuthorize("@ownership.canModify(authentication, @postService.authorIdOf(#id))")
 * &#64;PreAuthorize("@ownership.canModify(authentication, #post)")   // #post 가 OwnedResource 일 때
 * </pre>
 *
 * SUSPENDED 사용자의 쓰기 차단(B3)은 여기서가 아니라 보안 체인의 {@link SuspendedUserWriteFilter} 가 모든 쓰기 요청에 대해 먼저 한다.
 */
@Component("ownership")
public class OwnershipChecker {

	/** 본인이거나 ADMIN 이면 true. 소유자가 없는 리소스(ownerId null)는 ADMIN 만. */
	public boolean canModify(Authentication authentication, Long ownerId) {
		AuthUser user = authUser(authentication);
		if (user == null) {
			return false;
		}
		return user.isAdmin() || (ownerId != null && ownerId.equals(user.id()));
	}

	public boolean canModify(Authentication authentication, OwnedResource resource) {
		return resource != null && canModify(authentication, resource.getOwnerId());
	}

	/** ADMIN 여부와 상관없이 본인 소유인지만 본다. */
	public boolean isOwner(Authentication authentication, Long ownerId) {
		AuthUser user = authUser(authentication);
		return user != null && ownerId != null && ownerId.equals(user.id());
	}

	private static AuthUser authUser(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()) {
			return null;
		}
		return authentication.getPrincipal() instanceof AuthUser user ? user : null;
	}

}
