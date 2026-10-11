package com.example.board.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * 401/403 을 JSON 으로 응답한다. 로그인 페이지 리다이렉트는 없다(SPA·비브라우저 클라이언트 공용 API).
 * 401 에는 RFC 6750 {@code WWW-Authenticate: Bearer ...} 헤더도 붙는다.
 */
public final class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		bearerEntryPoint.commence(request, response, ex);
		write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "인증이 필요합니다");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		if (ex instanceof CsrfException) {
			write(response, HttpStatus.FORBIDDEN, "csrf", "CSRF 토큰이 없거나 올바르지 않습니다");
		}
		else if (ex instanceof StepUpRequiredException) {
			write(response, HttpStatus.FORBIDDEN, StepUpRequiredException.CODE, StepUpRequiredException.MESSAGE);
		}
		else if (ex instanceof SuspendedUserException) {
			write(response, HttpStatus.FORBIDDEN, "suspended", "정지된 계정은 쓰기 요청을 할 수 없습니다");
		}
		else {
			write(response, HttpStatus.FORBIDDEN, "forbidden", "권한이 없습니다");
		}
	}

	private static void write(HttpServletResponse response, HttpStatus status, String error, String message)
			throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(status.value());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter()
			.write("{\"status\":" + status.value() + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
	}

}
