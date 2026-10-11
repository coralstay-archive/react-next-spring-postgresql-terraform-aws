package com.example.board.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * "Bearer 경로" 판정 (decision-5). {@code Authorization} 헤더가 {@code Bearer} 스킴이면 토큰이 맞든 틀리든 Bearer 경로다.
 * Bearer 경로는 세션을 읽거나 쓰지 않고, CSRF 검사에서 빠지며, 토큰이 틀리면 세션이 있어도 401 이다.
 */
public final class BearerRequests {

	public static final RequestMatcher MATCHER = BearerRequests::isBearer;

	private BearerRequests() {
	}

	public static boolean isBearer(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		return header != null && header.regionMatches(true, 0, "Bearer", 0, 6)
				&& (header.length() == 6 || Character.isWhitespace(header.charAt(6)));
	}

}
