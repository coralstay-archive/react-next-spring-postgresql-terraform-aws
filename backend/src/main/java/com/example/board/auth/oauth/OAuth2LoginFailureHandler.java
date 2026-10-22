package com.example.board.auth.oauth;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
 *
 * <p>원인은 WARN 한 줄로 남긴다(TASK-25#2, BE-20): 등록 id(요청 경로의 마지막 조각), 돌려보낸 오류 코드, 예외 클래스 사슬, 원인 사슬의 제공자
 * HTTP 상태. 예외 메시지·error_description·제공자 응답 본문·인가 코드·state·토큰·이메일은 싣지 않는다({@link OAuth2Logs}).
 */
public final class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

	public static final String GENERIC = "oauth2_failed";

	private static final Pattern CODE = Pattern.compile("[a-z0-9_]{1,64}");

	private static final Pattern REGISTRATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

	private static final Log log = LogFactory.getLog(OAuth2LoginFailureHandler.class);

	private final OAuth2Redirects redirects;

	public OAuth2LoginFailureHandler(OAuth2Redirects redirects) {
		this.redirects = redirects;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String code = errorCode(exception);
		boolean linking = LinkIntent.take(request) != null;
		log.warn("OAuth 로그인 실패 → ?error=" + code + ": registration=" + registrationId(request) + " mode="
				+ (linking ? "link" : "login") + OAuth2Logs.httpStatus(exception) + " 오류=" + OAuth2Logs.causes(exception));
		response.sendRedirect(linking ? redirects.linkFailed(code) : redirects.loginFailed(code));
	}

	/** 콜백·인가 시작 경로의 마지막 조각(등록 id). 모양이 이상하면(사용자 입력) {@code ?}. */
	private static String registrationId(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String last = uri == null ? "" : uri.substring(uri.lastIndexOf('/') + 1);
		return REGISTRATION_ID.matcher(last).matches() ? last : "?";
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
