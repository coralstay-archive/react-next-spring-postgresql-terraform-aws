package com.example.board.auth.oauth;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.user.User;
import com.example.board.user.UserStatus;

/**
 * OAuth 콜백 성공(토큰 교환, id_token·userinfo 검증까지 끝남) 뒤의 처리.
 *
 * <p>스프링이 만든 {@link OAuth2AuthenticationToken} 은 세션에 저장하지 않는다(로그인 필터의 저장소를 Null 로 바꿈,
 * {@link OAuth2LoginCustomizer}). 대신 제공자 응답을 매퍼로 정규화하고 {@link IdentityResolver} 로 우리 계정을 찾거나 만든 뒤,
 * LOCAL 로그인과 같은 {@link SessionLogin} 으로 세션을 만든다(세션 ID 교체 + {@code AuthUser} 저장 + CSRF 토큰 교체). 그래서
 * 이후 요청의 principal 은 수단과 상관없이 항상 {@code AuthUser} 다.
 *
 * <p>SUSPENDED 계정은 세션을 만들지 않고 {@code ?error=suspended} 로 돌려보낸다.
 */
public final class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

	public static final String SUSPENDED = "suspended";

	public static final String INVALID_USER_INFO = "invalid_user_info";

	private final Map<String, OAuth2UserInfoMapper> mappers;

	private final IdentityResolver resolver;

	private final SessionLogin sessionLogin;

	private final OAuth2Redirects redirects;

	public OAuth2LoginSuccessHandler(List<OAuth2UserInfoMapper> mappers, IdentityResolver resolver,
			SessionLogin sessionLogin, OAuth2Redirects redirects) {
		this.mappers = mappers.stream()
			.collect(Collectors.toUnmodifiableMap(OAuth2UserInfoMapper::registrationId, Function.identity()));
		this.resolver = resolver;
		this.sessionLogin = sessionLogin;
		this.redirects = redirects;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// 스프링의 OAuth2 인증은 이 요청 안에서도 쓰지 않는다.
		SecurityContextHolder.clearContext();
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
		OAuth2UserInfo info = userInfo(token);
		if (info == null) {
			response.sendRedirect(redirects.loginFailed(INVALID_USER_INFO));
			return;
		}
		User user = resolveForLogin(info);
		if (user.getStatus() != UserStatus.ACTIVE) {
			response.sendRedirect(redirects.loginFailed(SUSPENDED));
			return;
		}
		sessionLogin.login(user, request, response);
		response.sendRedirect(redirects.loginSucceeded());
	}

	private OAuth2UserInfo userInfo(OAuth2AuthenticationToken token) {
		OAuth2UserInfoMapper mapper = mappers.get(token.getAuthorizedClientRegistrationId());
		if (mapper == null) {
			return null;
		}
		try {
			return mapper.map(token.getPrincipal());
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	/** 같은 새 identity 로 동시에 들어와 UNIQUE 에 진 쪽은 이긴 쪽이 만든 계정으로 다시 찾는다. */
	private User resolveForLogin(OAuth2UserInfo info) {
		try {
			return resolver.resolveForLogin(info);
		}
		catch (DataIntegrityViolationException ex) {
			return resolver.resolveForLogin(info);
		}
	}

}
