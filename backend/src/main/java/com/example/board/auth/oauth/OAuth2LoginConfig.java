package com.example.board.auth.oauth;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.user.UserRepository;

/** 소셜 로그인 빈 (TASK-4). 제공자는 {@link OAuth2UserInfoMapper} 빈 + {@code app.oauth2.registrations.<id>.*} 설정으로 붙는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ OAuth2Properties.class, OAuth2HttpProperties.class })
public class OAuth2LoginConfig {

	@Bean
	PublicUrls publicUrls(@Value("${app.public-base-url}") String publicBaseUrl) {
		return new PublicUrls(publicBaseUrl);
	}

	@Bean
	OAuth2ClientRegistrations clientRegistrationRepository(OAuth2Properties properties,
			List<OAuth2UserInfoMapper> mappers, PublicUrls urls) {
		return OAuth2ClientRegistrations.from(properties, mappers, urls);
	}

	@Bean
	OAuth2Redirects oauth2Redirects(PublicUrls urls, OAuth2Properties properties) {
		return new OAuth2Redirects(urls, properties);
	}

	@Bean
	OAuth2LoginSuccessHandler oauth2LoginSuccessHandler(List<OAuth2UserInfoMapper> mappers, IdentityResolver resolver,
			SessionLogin sessionLogin, OAuth2Redirects redirects, UserRepository users, Clock clock) {
		return new OAuth2LoginSuccessHandler(mappers, resolver, sessionLogin, redirects, users, clock);
	}

	@Bean
	OAuth2LoginFailureHandler oauth2LoginFailureHandler(OAuth2Redirects redirects) {
		return new OAuth2LoginFailureHandler(redirects);
	}

	/** 제공자 HTTP 호출 타임아웃 (TASK-25#3). */
	@Bean
	OAuth2HttpClients oauth2HttpClients(OAuth2HttpProperties properties) {
		return new OAuth2HttpClients(properties);
	}

	/**
	 * id_token 디코더(JWKS 조회에 타임아웃). oauth2Login 설정이 이 타입의 빈을 찾아 OIDC 인증 공급자에 건다 — 빈으로만 바꿀 수 있다.
	 */
	@Bean
	JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(OAuth2HttpClients clients) {
		return clients.idTokenDecoderFactory();
	}

	@Bean
	OAuth2LoginCustomizer oauth2LoginCustomizer(OAuth2ClientRegistrations registrations,
			OAuth2LoginSuccessHandler successHandler, OAuth2LoginFailureHandler failureHandler,
			OAuth2HttpClients clients) {
		return new OAuth2LoginCustomizer(registrations, successHandler, failureHandler, clients);
	}

}
