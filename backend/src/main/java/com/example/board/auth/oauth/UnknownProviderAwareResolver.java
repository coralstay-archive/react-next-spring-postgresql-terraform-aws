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
 * 기본 인가 요청 resolver 에 "설정되지 않은 제공자" 오류 코드를 붙인다. 기본은 IllegalArgumentException 이라 실패 핸들러가
 * 이유를 알 수 없다 → {@code unknown_provider}.
 */
class UnknownProviderAwareResolver implements OAuth2AuthorizationRequestResolver {

	public static final String UNKNOWN_PROVIDER = "unknown_provider";

	private final DefaultOAuth2AuthorizationRequestResolver delegate;

	UnknownProviderAwareResolver(ClientRegistrationRepository registrations) {
		this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
				OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
		try {
			return delegate.resolve(request);
		}
		catch (IllegalArgumentException ex) {
			throw unknownProvider(ex);
		}
	}

	@Override
	public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
		try {
			return delegate.resolve(request, clientRegistrationId);
		}
		catch (IllegalArgumentException ex) {
			throw unknownProvider(ex);
		}
	}

	private static OAuth2AuthenticationException unknownProvider(IllegalArgumentException cause) {
		return new OAuth2AuthenticationException(new OAuth2Error(UNKNOWN_PROVIDER), cause);
	}

}
