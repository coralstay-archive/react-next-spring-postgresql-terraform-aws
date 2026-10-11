package com.example.board.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * 스프링 기본 인가 요청 resolver 를 감싼다.
 *
 * <ul>
 * <li>{@code ?link=true} 없이 인가를 시작하면(일반 로그인) 세션에 남은 {@link LinkIntent} 를 버린다. 연결 시작 엔드포인트만
 * {@code ?link=true} 를 붙여 보낸다.</li>
 * <li>설정되지 않은 제공자: 기본은 IllegalArgumentException 이라 실패 핸들러가 이유를 알 수 없다 → OAuth2 오류
 * {@code unknown_provider} 로 바꾼다.</li>
 * </ul>
 */
class LinkAwareAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

	public static final String UNKNOWN_PROVIDER = "unknown_provider";

	/** 연결 모드로 시작한다는 표시. 인가 요청(제공자로 가는 URL)에는 실리지 않는다. */
	public static final String LINK_PARAMETER = "link";

	private final DefaultOAuth2AuthorizationRequestResolver delegate;

	LinkAwareAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
		this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		try {
			return afterResolve(request, delegate.resolve(request));
		}
		catch (IllegalArgumentException ex) {
			throw unknownProvider(ex);
		}
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		try {
			return afterResolve(request, delegate.resolve(request, clientRegistrationId));
		}
		catch (IllegalArgumentException ex) {
			throw unknownProvider(ex);
		}
	}

	private static OAuth2AuthorizationRequest afterResolve(HttpServletRequest request,
			OAuth2AuthorizationRequest authorizationRequest) {
		if (authorizationRequest != null && !"true".equals(request.getParameter(LINK_PARAMETER))) {
			LinkIntent.discard(request);
		}
		return authorizationRequest;
	}

	private static OAuth2AuthenticationException unknownProvider(IllegalArgumentException cause) {
		return new OAuth2AuthenticationException(new OAuth2Error(UNKNOWN_PROVIDER), cause);
	}

}
