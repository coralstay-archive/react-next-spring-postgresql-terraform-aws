package com.example.board.auth.oauth;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * OAuth 인가 시작·콜백 실패를 프론트로 302 ({@code ?error=코드}). 스프링 기본(로그인 페이지 {@code /login?error})이나 401 JSON 을
 * 쓰지 않는다 — 브라우저 내비게이션이기 때문이다.
 *
 * <p>연결 모드({@link LinkIntent})였으면 로그인 화면 대신 계정 화면으로 보낸다(플래그는 지운다).
 *
 * <p>코드는 {@link OAuth2AuthenticationException} 의 OAuth2 오류 코드다(예: {@code access_denied},
 * {@code authorization_request_not_found}, {@code invalid_id_token}, {@code invalid_nonce}, 우리가 만드는
 * {@code unknown_provider}). 모양이 이상하면 {@code oauth2_failed}.
 */
public final class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

	public static final String GENERIC = "oauth2_failed";

	private static final Pattern CODE = Pattern.compile("[a-z0-9_]{1,64}");

	private final OAuth2Redirects redirects;

	public OAuth2LoginFailureHandler(OAuth2Redirects redirects) {
		this.redirects = redirects;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String code = errorCode(exception);
		boolean linking = LinkIntent.take(request) != null;
		response.sendRedirect(linking ? redirects.linkFailed(code) : redirects.loginFailed(code));
	}

	/** 원인 사슬에서 처음 만나는 OAuth2 오류 코드. */
	static String errorCode(Throwable exception) {
		for (Throwable t = exception; t != null; t = t.getCause()) {
			if (t instanceof OAuth2AuthenticationException oauth2) {
				String code = oauth2.getError().getErrorCode();
				return code != null && CODE.matcher(code).matches() ? code : GENERIC;
			}
		}
		return GENERIC;
	}

}
