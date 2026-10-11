package com.example.board.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextRepository;

import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;

/**
 * 단일 SecurityFilterChain (decision-5).
 *
 * <ul>
 * <li>{@code Authorization: Bearer} 가 있으면 Resource Server(자체 서명 JWT)로 인증한다. 세션은 읽지도 쓰지도 않는다.</li>
 * <li>없으면 Spring Session JDBC 가 감싼 HttpSession 의 SecurityContext 로 인증한다.</li>
 * <li>미인증은 401 JSON, 권한 없음은 403 JSON. 로그인 페이지·리다이렉트·HTTP Basic 은 없다.</li>
 * </ul>
 * principal 은 두 경로 모두 {@link com.example.board.auth.AuthUser} 다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new SessionUnlessBearerSecurityContextRepository();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtTokenService tokens) {
		return tokens::decode;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens,
			SecurityContextRepository securityContextRepository, JwtDecoder jwtDecoder) throws Exception {
		JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler();

		http.securityContext(context -> context.securityContextRepository(securityContextRepository))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			.requestCache(cache -> cache.disable())
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.anonymous(anonymous -> {
			})
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(tokens::toAuthentication))
				.authenticationEntryPoint(errors)
				.accessDeniedHandler(errors))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
			.authorizeHttpRequests(authorize -> authorize
				// 공개 엔드포인트. 새 공개 API(예: 게시글 목록, 로그인)는 여기에 명시적으로 더한다.
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/auth/csrf").permitAll()
				.requestMatchers("/error").permitAll()
				// 기본은 인증 필요 (deny-by-default). 세부 인가는 @PreAuthorize.
				.anyRequest().authenticated());
		return http.build();
	}

}
