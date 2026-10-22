package com.example.board.auth.oauth;

import java.net.http.HttpClient;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * OAuth 제공자 HTTP 클라이언트 (TASK-25#3, BE-21). 스프링 기본 클라이언트는 타임아웃이 없어(JDK 기본: 무한) 제공자가 멈추면 콜백 요청
 * 스레드가 무한히 매달린다. 같은 요청 팩토리(JDK HttpClient 연결 타임아웃 + 읽기 타임아웃)를 세 곳에 건다.
 *
 * <ul>
 * <li>토큰 교환: 스프링 기본과 같은 변환기(form + 토큰 응답)·오류 처리기를 쓴 {@link RestClient}. 타임아웃이면
 * {@code invalid_token_response}.</li>
 * <li>userinfo: {@link DefaultOAuth2UserService}(OIDC 는 {@link OidcUserService} 가 위임)의 {@link RestTemplate}. 타임아웃이면
 * {@code invalid_user_info_response}.</li>
 * <li>id_token 검증의 JWKS: {@link OidcIdTokenDecoderFactory} 는 HTTP 클라이언트를 바꿀 수 없어서 같은 규칙(RS256, 기본 검증기
 * {@code JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(registration))}, 기본 클레임 변환기)으로
 * {@link NimbusJwtDecoder} 를 직접 만든다. 타임아웃이면 {@code invalid_id_token}. 우리 등록은 모두 RS256 이다(HS256 id_token 미사용).</li>
 * </ul>
 * 모든 실패는 실패 핸들러가 프론트 콜백 {@code ?error=코드} 로 돌려보낸다(B1).
 */
public final class OAuth2HttpClients {

	private final HttpClient httpClient;

	private final ClientHttpRequestFactory requestFactory;

	public OAuth2HttpClients(OAuth2HttpProperties properties) {
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(this.httpClient);
		factory.setReadTimeout(properties.readTimeout());
		this.requestFactory = factory;
	}

	HttpClient httpClient() {
		return httpClient;
	}

	public RestClientAuthorizationCodeTokenResponseClient tokenResponseClient() {
		RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
		client.setRestClient(RestClient.builder()
			.requestFactory(requestFactory)
			.configureMessageConverters(converters -> converters.addCustomConverter(new FormHttpMessageConverter())
				.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
			.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
			.build());
		return client;
	}

	public DefaultOAuth2UserService userService() {
		RestTemplate restTemplate = new RestTemplate(requestFactory);
		restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
		DefaultOAuth2UserService service = new DefaultOAuth2UserService();
		service.setRestOperations(restTemplate);
		return service;
	}

	public OidcUserService oidcUserService() {
		OidcUserService service = new OidcUserService();
		service.setOauth2UserService(userService());
		return service;
	}

	public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
		RestTemplate jwks = new RestTemplate(requestFactory);
		return registration -> {
			String jwkSetUri = registration.getProviderDetails().getJwkSetUri();
			if (!StringUtils.hasText(jwkSetUri)) {
				OAuth2Error error = new OAuth2Error("missing_signature_verifier",
						"Failed to find a Signature Verifier for Client Registration: '"
								+ registration.getRegistrationId() + "'. Check to ensure you have configured the JwkSet URI.",
						null);
				throw new OAuth2AuthenticationException(error, error.toString());
			}
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
				.jwsAlgorithm(SignatureAlgorithm.RS256)
				.restOperations(jwks)
				.build();
			decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(registration)));
			decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
			return (JwtDecoder) decoder;
		};
	}

}
