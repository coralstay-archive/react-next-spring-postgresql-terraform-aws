package com.example.board.auth;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 인증 완료 상태의 {@link AuthUser} 토큰. JWT 컨버터와 (TASK-3 이후) 세션 로그인 모두 이것을 SecurityContext 에 넣는다.
 * 권한은 {@code ROLE_USER} 또는 {@code ROLE_ADMIN} 하나다.
 */
public final class AuthUserAuthentication extends AbstractAuthenticationToken {

	private static final long serialVersionUID = 1L;

	private final AuthUser principal;

	private AuthUserAuthentication(AuthUser principal) {
		super(List.of(new SimpleGrantedAuthority(principal.role().authority())));
		this.principal = principal;
		setAuthenticated(true);
	}

	public static AuthUserAuthentication of(AuthUser user) {
		return new AuthUserAuthentication(user);
	}

	@Override
	public AuthUser getPrincipal() {
		return principal;
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return String.valueOf(principal.id());
	}

}
