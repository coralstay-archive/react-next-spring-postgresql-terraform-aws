package com.example.board.auth.oauth;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.user.UserRepository;

/** 소셜 로그인 빈 (TASK-4). 제공자는 {@link OAuth2UserInfoMapper} 빈 + {@code app.oauth2.registrations.<id>.*} 설정으로 붙는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2Properties.class)
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
			SessionLogin sessionLogin, OAuth2Redirects redirects, UserRepository users) {
		return new OAuth2LoginSuccessHandler(mappers, resolver, sessionLogin, redirects, users);
	}

	@Bean
	OAuth2LoginFailureHandler oauth2LoginFailureHandler(OAuth2Redirects redirects) {
		return new OAuth2LoginFailureHandler(redirects);
	}

	@Bean
	OAuth2LoginCustomizer oauth2LoginCustomizer(OAuth2ClientRegistrations registrations,
			OAuth2LoginSuccessHandler successHandler, OAuth2LoginFailureHandler failureHandler) {
		return new OAuth2LoginCustomizer(registrations, successHandler, failureHandler);
	}

}
