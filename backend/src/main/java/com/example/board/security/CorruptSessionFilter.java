package com.example.board.security;

import java.io.IOException;
import java.util.Collections;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 역직렬화할 수 없는 세션은 세션 없음·만료와 같게 처리한다 (TASK-24#5, BE-11).
 *
 * <p>Spring Session JDBC 는 세션 속성(SecurityContext 등, JDK 직렬화)을 처음 읽을 때 역직렬화한다. 배포 뒤 클래스가 바뀌거나 사라졌거나
 * (Spring Security 업그레이드, {@code AuthUser} 변경) 바이트가 깨졌으면 보안 체인이 SecurityContext 를 읽다가
 * {@link ConversionException}·{@link SerializationFailedException} 으로 터져, 그 쿠키의 모든 요청이 500 이었다.
 *
 * <p>이 필터는 Spring Session 필터 바로 뒤·보안 체인 앞에서 세션 속성을 한 번씩 읽어 보고, 실패하면 그 세션을 무효화한다(저장소에서
 * 삭제 + {@code SESSION} 쿠키 만료). 요청은 세션 없이 계속된다 — 보호된 API 는 보안 체인이 표준 401 {@code unauthorized} JSON 을,
 * 공개 API 는 익명 응답을 낸다. 새 오류 코드를 만들지 않은 이유: 클라이언트(프론트)는 이미 401 {@code unauthorized} 를 "로그인이
 * 풀렸다"로 다루고, 배포 직후 기존 쿠키를 가진 모든 사용자가 공개 페이지까지 오류를 받지 않게 하려는 것이다.
 *
 * <p>Bearer 요청은 세션을 읽지 않으므로(decision-5) 건드리지 않는다. 로그에는 속성 이름과 원인 예외 종류만 남긴다(세션 ID 는 자격이라
 * 남기지 않는다).
 */
public class CorruptSessionFilter extends OncePerRequestFilter {

	private static final Log log = LogFactory.getLog(CorruptSessionFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!BearerRequests.isBearer(request)) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				invalidateIfUnreadable(session);
			}
		}
		chain.doFilter(request, response);
	}

	private static void invalidateIfUnreadable(HttpSession session) {
		for (String name : Collections.list(session.getAttributeNames())) {
			try {
				session.getAttribute(name);
			}
			catch (ConversionException | SerializationFailedException ex) {
				Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
				log.warn("역직렬화할 수 없는 세션을 무효화한다: attribute=" + name + " cause=" + cause.getClass().getName());
				session.invalidate();
				return;
			}
		}
	}

}
