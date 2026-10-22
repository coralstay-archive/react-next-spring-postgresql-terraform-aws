package com.example.board.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.example.board.auth.jwt.JwtTokenService;

/**
 * 만료된 Bearer 헤더를 일부 경로에서 무시한다 (TASK-24#1, BE-15).
 *
 * <p>access 토큰이 만료된 클라이언트가 헤더를 그대로 붙여 refresh·로그아웃·공개 GET 을 부르면, 지금까지는 인증 필터가 먼저 401 을 냈다
 * (만료돼서 새 토큰을 받으려는 refresh 까지 막히고, 로그아웃해도 refresh family 가 남았다). 이 경로들({@code lenient})에서는 토큰이
 * <em>만료만</em> 됐으면({@link JwtTokenService#expiredOnly}) 토큰이 없는 것으로 보고 익명으로 계속한다. 서명·발급자·형식이 틀린 토큰은
 * 그대로 401 이고(위조·클라이언트 버그를 숨기지 않는다), 보호된 API 의 만료 토큰도 그대로 401 이다.
 *
 * <p>헤더는 그대로 있으므로 이 요청은 여전히 Bearer 경로다({@link BearerRequests}: 세션을 읽지 않고 CSRF 면제).
 */
public final class LenientBearerTokenResolver implements BearerTokenResolver {

	private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();

	private final RequestMatcher lenient;

	private final JwtTokenService tokens;

	public LenientBearerTokenResolver(RequestMatcher lenient, JwtTokenService tokens) {
		this.lenient = lenient;
		this.tokens = tokens;
	}

	@Override
	public String resolve(HttpServletRequest request) {
		String token = delegate.resolve(request);
		if (token != null && lenient.matches(request) && tokens.expiredOnly(token).isPresent()) {
			return null;
		}
		return token;
	}

}
