package com.example.board.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.example.board.user.User;

/**
 * 웹 세션 로그인·로그아웃 (decision-5). 폼 로그인 필터 대신 API 컨트롤러가 직접 하므로, 스프링 시큐리티가
 * 폼 로그인에서 해 주던 일을 여기서 같은 순서로 한다.
 *
 * <ol>
 * <li>세션 고정 방지: 기존 세션이 있으면 ID 를 바꾼다({@code changeSessionId}). Spring Session JDBC 도 지원한다.</li>
 * <li>{@link AuthUserAuthentication} 을 SecurityContext 에 넣고 {@link SecurityContextRepository} 빈으로 세션에 저장한다.
 * (세션이 없으면 이때 만들어지고 {@code SESSION} 쿠키가 나간다.)</li>
 * <li>CSRF 토큰 교체: 로그인 전 토큰을 버리고 새 토큰을 XSRF-TOKEN 쿠키로 내려준다({@code CsrfAuthenticationStrategy} 와 같은
 * 목적). SPA 는 로그인 응답 뒤 쿠키를 다시 읽으면 된다.</li>
 * </ol>
 */
@Component
public class SessionLogin {

	/**
	 * 이번 요청이 세션 로그인한 사용자 id (요청 속성). 세션이 저장된 뒤 {@code SessionLoginRecheckFilter} 가 status 를 다시 본다
	 * (TASK-24#3, 로그인·정지 경쟁).
	 */
	public static final String LOGGED_IN_USER_ID = SessionLogin.class.getName() + ".LOGGED_IN_USER_ID";

	private final SecurityContextRepository contextRepository;

	private final CsrfTokenRepository csrfTokens;

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	public SessionLogin(SecurityContextRepository contextRepository, CsrfTokenRepository csrfTokens) {
		this.contextRepository = contextRepository;
		this.csrfTokens = csrfTokens;
	}

	public void login(User user, HttpServletRequest request, HttpServletResponse response) {
		login(user, false, request, response);
	}

	/**
	 * {@code stepUpRequired} 면 제한 세션(TASK-7 B4)이다. 제한 없는 로그인(step-up, 다른 수단 로그인)은 같은 방법으로 세션 ID 를 바꾸며
	 * principal 을 덮어쓴다.
	 */
	public void login(User user, boolean stepUpRequired, HttpServletRequest request, HttpServletResponse response) {
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		SecurityContext context = holder.createEmptyContext();
		context.setAuthentication(AuthUserAuthentication.of(AuthUser.from(user, stepUpRequired)));
		holder.setContext(context);
		contextRepository.saveContext(context, request, response);
		request.setAttribute(LOGGED_IN_USER_ID, user.getId());
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
	}

	/**
	 * 이번 요청이 {@link #login} 을 호출했으면(요청 속성 {@link #LOGGED_IN_USER_ID}) 그 뒤 실패한 경우에 쓴다: 세션에 넣은 인증을 걷어 내서
	 * 오류 응답에 로그인된 세션이 실려 나가지 않게 한다(TASK-25). 이번 요청이 로그인하지 않았으면 아무것도 하지 않는다 — 기존 세션(제한 세션,
	 * 연결하려던 로그인 사용자)의 인증은 이 요청이 만든 것이 아니므로 남긴다. 걷어 내기 자체가 실패하면 그 예외를 던진다(호출자가 원래 오류를
	 * 유지하며 기록).
	 *
	 * @return 이번 요청이 로그인했었는지
	 */
	public boolean discardLogin(HttpServletRequest request) {
		if (request.getAttribute(LOGGED_IN_USER_ID) == null) {
			return false;
		}
		holder.clearContext();
		request.removeAttribute(LOGGED_IN_USER_ID);
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		}
		return true;
	}

	/**
	 * 세션을 무효화한다. Spring Session 이 저장소의 세션을 지우고 {@code SESSION} 쿠키를 만료시키는 Set-Cookie 를 보낸다.
	 * CSRF 토큰 쿠키도 지운다(로그아웃 후 첫 쓰기 전에 SPA 가 {@code /api/auth/csrf} 를 다시 부른다).
	 */
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		holder.clearContext();
		csrfTokens.saveToken(null, request, response);
	}

}
