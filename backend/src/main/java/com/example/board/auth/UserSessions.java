package com.example.board.auth;

import java.util.Set;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * 한 사용자의 웹 세션(Spring Session JDBC)을 모두 지운다. 세션은 principal name 인덱스({@code SPRING_SESSION.PRINCIPAL_NAME})로
 * 찾는다. Spring Session 이 세션의 SecurityContext 에서 {@code Authentication#getName()}(= {@link AuthUserAuthentication} 의 사용자
 * id)을 뽑아 채우므로 LOCAL·소셜·OTP 세션 로그인이 모두 {@code String.valueOf(userId)} 로 색인된다.
 */
@Component
public class UserSessions {

	private final FindByIndexNameSessionRepository<?> sessions;

	public UserSessions(FindByIndexNameSessionRepository<?> sessions) {
		this.sessions = sessions;
	}

	/** 지운 세션 수. */
	public int deleteAll(Long userId) {
		Set<String> ids = sessions.findByPrincipalName(String.valueOf(userId)).keySet();
		ids.forEach(sessions::deleteById);
		return ids.size();
	}

}
