package com.example.board.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.client.OAuth2LoginConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

/**
 * 보안 체인의 {@code oauth2Login} 설정 (TASK-4). {@code SecurityConfig} 가 {@code .oauth2Login(customizer)} 로 쓴다.
 *
 * <ul>
 * <li>인가 시작 {@code GET /api/oauth2/authorization/{id}}, 콜백 {@code GET /api/login/oauth2/code/{id}} (스프링 기본 경로).
 * 인가 요청(state, nonce)은 기본대로 HttpSession(Spring Session JDBC)에 둔다.</li>
 * <li>성공·실패는 프론트로 302 ({@link OAuth2LoginSuccessHandler}, {@link OAuth2LoginFailureHandler}). 기본 로그인 페이지는
 * 보안 체인에 명시적인 401 entry point 가 있어 만들어지지 않는다.</li>
 * <li>로그인 필터가 {@code OAuth2AuthenticationToken} 을 세션에 저장하지 않게 저장소를 Null 로 바꾼다. 세션 로그인은 성공 핸들러가
 * {@code SessionLogin} 으로 한다. (oauth2Login 의 {@code securityContextRepository()} 는 체인 공통 저장소로 덮이므로
 * ObjectPostProcessor 로 바꾼다.)</li>
 * <li>제공자 access token 은 보관하지 않는다 — 로그인 뒤 제공자 API 를 부르지 않는다(no-op authorized client 저장소).</li>
 * </ul>
 */
public final class OAuth2LoginCustomizer implements Customizer<OAuth2LoginConfigurer<HttpSecurity>> {

	private final OAuth2ClientRegistrations registrations;

	private final OAuth2LoginSuccessHandler successHandler;

	private final OAuth2LoginFailureHandler failureHandler;

	public OAuth2LoginCustomizer(OAuth2ClientRegistrations registrations, OAuth2LoginSuccessHandler successHandler,
			OAuth2LoginFailureHandler failureHandler) {
		this.registrations = registrations;
		this.successHandler = successHandler;
		this.failureHandler = failureHandler;
	}

	@Override
	public void customize(OAuth2LoginConfigurer<HttpSecurity> oauth2) {
		oauth2.clientRegistrationRepository(registrations)
			.authorizedClientRepository(new DiscardingAuthorizedClientRepository())
			.authorizationEndpoint(endpoint -> endpoint
				.authorizationRequestResolver(new LinkAwareAuthorizationRequestResolver(registrations)))
			.successHandler(successHandler)
			.failureHandler(failureHandler)
			.withObjectPostProcessor(new LoginFilterPostProcessor())
			.withObjectPostProcessor(new RedirectFilterPostProcessor(failureHandler));
	}

	/** 콜백 필터: 스프링의 인증 결과를 세션에 저장하지 않는다. */
	static final class LoginFilterPostProcessor implements ObjectPostProcessor<OAuth2LoginAuthenticationFilter> {

		@Override
		public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
			filter.setSecurityContextRepository(new NullSecurityContextRepository());
			return filter;
		}

	}

	/** 인가 시작 필터: 실패(설정 안 된 제공자 등)도 기본 500 대신 프론트로 302. */
	static final class RedirectFilterPostProcessor implements ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter> {

		private final OAuth2LoginFailureHandler failureHandler;

		RedirectFilterPostProcessor(OAuth2LoginFailureHandler failureHandler) {
			this.failureHandler = failureHandler;
		}

		@Override
		public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
			filter.setAuthenticationFailureHandler(failureHandler);
			return filter;
		}

	}

	/** 제공자 access token 을 어디에도 두지 않는다. */
	static final class DiscardingAuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

		@Override
		public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
				Authentication principal, HttpServletRequest request) {
			return null;
		}

		@Override
		public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
				HttpServletRequest request, HttpServletResponse response) {
		}

		@Override
		public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
				HttpServletRequest request, HttpServletResponse response) {
		}

	}

}
