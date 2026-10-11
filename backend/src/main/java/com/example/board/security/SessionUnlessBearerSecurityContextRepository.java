package com.example.board.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Bearer 요청이면 요청 범위 저장소만, 아니면 세션(Spring Session JDBC가 감싼 HttpSession)까지 쓰는 SecurityContext 저장소.
 *
 * <p>이렇게 해서 Bearer 요청은 세션 쿠키가 같이 와도 세션의 인증을 읽지 않고(잘못된 토큰이 세션으로 "구제"되지 않는다),
 * 세션을 만들거나 바꾸지도 않는다. 로그인 API(TASK-3)는 이 빈으로 {@code saveContext} 를 호출해 세션에 저장한다.
 */
public final class SessionUnlessBearerSecurityContextRepository implements SecurityContextRepository {

	private final SecurityContextRepository requestOnly = new RequestAttributeSecurityContextRepository();

	private final SecurityContextRepository withSession = new DelegatingSecurityContextRepository(
			new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());

	private SecurityContextRepository pick(HttpServletRequest request) {
		return BearerRequests.isBearer(request) ? requestOnly : withSession;
	}

	@Override
	@Deprecated
	@SuppressWarnings("deprecation")
	public SecurityContext loadContext(HttpRequestResponseHolder holder) {
		return pick(holder.getRequest()).loadContext(holder);
	}

	@Override
	public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
		return pick(request).loadDeferredContext(request);
	}

	@Override
	public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
		pick(request).saveContext(context, request, response);
	}

	@Override
	public boolean containsContext(HttpServletRequest request) {
		return pick(request).containsContext(request);
	}

}
