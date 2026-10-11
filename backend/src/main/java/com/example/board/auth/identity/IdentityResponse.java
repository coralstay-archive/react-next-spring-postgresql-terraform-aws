package com.example.board.auth.identity;

import java.time.Instant;

import com.example.board.user.AuthProvider;
import com.example.board.user.UserIdentity;

/** 내 로그인 수단 하나. subject(제공자 내부 id)는 내보내지 않는다. email 은 그 수단이 알려 준 값(없으면 null). */
public record IdentityResponse(Long id, AuthProvider provider, String email, Instant linkedAt) {

	public static IdentityResponse of(UserIdentity identity) {
		return new IdentityResponse(identity.getId(), identity.getProvider(), identity.getEmail(),
				identity.getLinkedAt());
	}

}
