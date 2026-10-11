package com.example.board.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.user.UserRepository;

/**
 * 단일 SecurityFilterChain (decision-5).
 *
 * <ul>
 * <li>{@code Authorization: Bearer} 가 있으면 Resource Server(자체 서명 JWT)로 인증한다. 세션은 읽지도 쓰지도 않는다.</li>
 * <li>없으면 Spring Session JDBC 가 감싼 HttpSession 의 SecurityContext 로 인증한다.</li>
 * <li>CSRF: 세션 경로만 검사한다. {@code XSRF-TOKEN} 쿠키(JS 가 읽음) 값을 {@code X-XSRF-TOKEN} 헤더로 보내는 더블 서브밋.
 * Bearer 경로는 면제한다(브라우저가 자동으로 붙이는 자격이 아니므로).</li>
 * <li>인가: 기본은 인증 필요. 역할·소유권은 {@code @PreAuthorize}(+ {@link OwnershipChecker}),
 * SUSPENDED 계정의 쓰기 차단은 {@link SuspendedUserWriteFilter} 가 모든 요청에 공통으로 한다.</li>
 * <li>미인증은 401 JSON, 권한 없음은 403 JSON. 로그인 페이지·리다이렉트·HTTP Basic 은 없다.</li>
 * </ul>
 * principal 은 두 경로 모두 {@link com.example.board.auth.AuthUser} 다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	/** 정지된 사용자도 할 수 있어야 하는 쓰기: 로그아웃 (TASK-3#3). */
	static final RequestMatcher LOGOUT = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/logout");

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new SessionUnlessBearerSecurityContextRepository();
	}

	/**
	 * {@code XSRF-TOKEN} 쿠키. 프론트 페이지(/)의 JS 가 읽어야 하므로 HttpOnly 가 아니고 path 가 {@code /} 다
	 * (기본값은 context path {@code /api} 라 페이지에서 보이지 않는다).
	 */
	@Bean
	CookieCsrfTokenRepository csrfTokenRepository(@Value("${app.security.cookie-secure:false}") boolean secure) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookiePath("/");
		repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secure));
		return repository;
	}

	/**
	 * 비밀번호 해시는 DelegatingPasswordEncoder 로만 만든다(기본 {@code {bcrypt}}, TASK-3 B4). 접두사가 있어 나중에 알고리즘을
	 * 바꿔도 기존 해시를 그대로 검증할 수 있다.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtTokenService tokens) {
		return tokens::decode;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenService tokens,
			SecurityContextRepository securityContextRepository, JwtDecoder jwtDecoder,
			CookieCsrfTokenRepository csrfTokenRepository, UserRepository users) throws Exception {
		JsonSecurityErrorHandler errors = new JsonSecurityErrorHandler();

		http.securityContext(context -> context.securityContextRepository(securityContextRepository))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
			// spa(): Spring Security 7 의 SPA 권장 구성(헤더면 원문 토큰, 폼 파라미터면 XOR 마스킹 토큰을 받는다).
			// 저장소만 위 쿠키 설정으로 바꾸고, Bearer 경로(토큰이 맞든 틀리든)만 CSRF 검사에서 뺀다.
			.csrf(csrf -> csrf.spa()
				.csrfTokenRepository(csrfTokenRepository)
				.ignoringRequestMatchers(BearerRequests.MATCHER, csrfExempt()))
			.requestCache(cache -> cache.disable())
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(tokens::toAuthentication))
				.authenticationEntryPoint(errors)
				.accessDeniedHandler(errors))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
			.authorizeHttpRequests(authorize -> authorize
				// 공개 엔드포인트. 새 공개 API(예: 게시글 목록, 로그인)는 여기에 명시적으로 더한다.
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/logout").permitAll()
				.requestMatchers("/error").permitAll()
				// 기본은 인증 필요 (deny-by-default). 세부 인가는 @PreAuthorize.
				.anyRequest().authenticated())
			// SUSPENDED 쓰기 차단(B3). 여기서 던진 AccessDeniedException 도 ExceptionTranslationFilter 가 403 JSON 으로 바꾼다.
			.addFilterAfter(new SuspendedUserWriteFilter(users, List.of(LOGOUT)), AuthorizationFilter.class);
		return http.build();
	}

	/**
	 * Bearer 헤더가 없어도 CSRF 검사를 하지 않는 "토큰 발급" 요청 (TASK-3).
	 *
	 * <p>{@code POST /auth/login?token=jwt}: CSRF 는 브라우저가 자동으로 붙이는 자격(쿠키)을 악용해 피해자 이름으로 상태를
	 * 바꾸는 공격이다. 이 요청은 (1) 인증되지 않은 요청이라 피해자 자격을 쓰지 않고, (2) 세션도 쿠키도 만들지 않으며
	 * (토큰은 응답 본문으로만 나간다), (3) 그 본문은 동일 출처 정책 때문에 다른 사이트가 읽을 수 없다. 공격자가 얻는 것이
	 * 없으므로 면제해도 된다. 반면 쿼리 없는 세션 로그인은 피해자 브라우저에 공격자 계정 세션을 심는 "로그인 CSRF" 가
	 * 가능하므로 검사를 유지한다. 판정은 쿼리 문자열만 본다(본문 파라미터로는 면제되지 않는다).
	 *
	 * <p>{@code POST /auth/token/refresh}(AC#5)도 같은 이유로 여기에 더한다.
	 */
	static RequestMatcher csrfExempt() {
		RequestMatcher login = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/login");
		return request -> login.matches(request) && "jwt".equals(queryParam(request, "token"));
	}

	private static String queryParam(HttpServletRequest request, String name) {
		String query = request.getQueryString();
		if (query == null) {
			return null;
		}
		return UriComponentsBuilder.newInstance().query(query).build().getQueryParams().getFirst(name);
	}

}
