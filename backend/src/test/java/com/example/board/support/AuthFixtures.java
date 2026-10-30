package com.example.board.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

import com.example.board.auth.AuthUser;
import com.example.board.auth.AuthUserAuthentication;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * 테스트 전용 인증 픽스처. 실제 로그인 API(TASK-3)가 생기기 전이라 계정은 리포지토리로 직접 만들고,
 * 세션은 Spring Session JDBC 저장소에 SecurityContext 를 넣어 만든 뒤 진짜 {@code SESSION} 쿠키로 보낸다.
 * Bearer 토큰은 운영 코드의 {@link JwtTokenService} 로 발급한다.
 */
@Component
public class AuthFixtures {

	private final UserRepository users;

	private final SessionRepository<? extends Session> sessions;

	private final JwtTokenService jwt;

	public AuthFixtures(UserRepository users, SessionRepository<? extends Session> sessions, JwtTokenService jwt) {
		this.users = users;
		this.sessions = sessions;
		this.jwt = jwt;
	}

	public User user() {
		return user(Role.USER, UserStatus.ACTIVE);
	}

	public User admin() {
		return user(Role.ADMIN, UserStatus.ACTIVE);
	}

	public User suspended() {
		return user(Role.USER, UserStatus.SUSPENDED);
	}

	public User user(Role role, UserStatus status) {
		User user = User.create("tester");
		user.changeRole(role);
		if (status == UserStatus.SUSPENDED) {
			user.suspend();
		}
		return users.saveAndFlush(user);
	}

	public String bearer(User user) {
		return jwt.issueAccessToken(AuthUser.from(user));
	}

	/** 제한(step-up 필요, TASK-7 B4) access 토큰. */
	public String stepUpBearer(User user) {
		return jwt.issueAccessToken(AuthUser.from(user, true));
	}

	/** 세션을 만들고 {@code SESSION=<base64 id>} 쿠키 문자열을 돌려준다. */
	public String sessionCookie(User user) {
		return sessionCookie(AuthUser.from(user));
	}

	/** 제한(step-up 필요, TASK-7 B4) 세션 — 휴대폰 로그인으로 만든 세션과 같다. */
	public String stepUpSessionCookie(User user) {
		return sessionCookie(AuthUser.from(user, true));
	}

	private String sessionCookie(AuthUser principal) {
		SecurityContext context = new SecurityContextImpl(AuthUserAuthentication.of(principal));
		String id = save(sessions, context);
		return "SESSION=" + Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
	}

	private static <S extends Session> String save(SessionRepository<S> repository, SecurityContext context) {
		S session = repository.createSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		repository.save(session);
		return session.getId();
	}

}
