package com.example.board.security;

import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.board.auth.UserSessions;
import com.example.board.auth.jwt.JwtProperties;
import com.example.board.auth.jwt.JwtTokenService;
import com.example.board.auth.oauth.OAuth2LoginCustomizer;
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
 * SUSPENDED 계정의 쓰기 차단은 {@link SuspendedUserWriteFilter}, 제한 세션(재활용 번호 보호, TASK-7 B4)의 쓰기 차단은
 * {@link StepUpRequiredWriteFilter} 가 모든 요청에 공통으로 한다.</li>
 * <li>미인증은 401 JSON, 권한 없음은 403 JSON. 로그인 페이지·리다이렉트·HTTP Basic 은 없다.</li>
 * <li>소셜 로그인(TASK-4): {@code oauth2Login} 필터가 {@code /oauth2/authorization/{id}} 와 {@code /login/oauth2/code/{id}} 만
 * 처리한다(인가 규칙보다 앞이라 permitAll 이 필요 없다). 성공·실패는 프론트로 302 ({@link OAuth2LoginCustomizer}).</li>
 * </ul>
 * principal 은 두 경로 모두 {@link com.example.board.auth.AuthUser} 다.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	/** 정지·제한 계정도 할 수 있어야 하는 쓰기: 로그아웃 (TASK-3#3). */
	static final RequestMatcher LOGOUT = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/logout");

	/**
	 * 로그인 계열 쓰기: LOCAL 로그인·가입, refresh, 휴대폰 OTP 발송·검증. 다른(또는 같은) 계정으로 새로 로그인하는 요청이라 지금 붙어 있는
	 * 세션·토큰의 제한(정지·step-up)과 상관없이 각 엔드포인트의 계약대로 판정한다.
	 */
	static final List<RequestMatcher> LOGIN_FAMILY = List.of(
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/login"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/signup"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/token/refresh"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/phone/otp"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/phone/verify"));

	/**
	 * 제한 세션·토큰(TASK-7 B4)도 할 수 있는 쓰기: 로그아웃, step-up, 다시 로그인하기(로그인 계열). 다시 로그인하면 새 세션·토큰으로
	 * 바뀌므로 제한을 우회하는 길이 아니다. (휴대폰 검증의 연결 모드는 컨트롤러가 제한 세션을 막는다.)
	 */
	static final List<RequestMatcher> STEP_UP_EXEMPT = Stream
		.concat(Stream.of(LOGOUT, PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/step-up/**")),
				LOGIN_FAMILY.stream())
		.toList();

	/**
	 * 정지 계정(TASK-2 B3)도 할 수 있는 쓰기 (TASK-24#4, BE-18): 로그아웃, 로그인 계열. 정지 계정의 세션·토큰이 남은 클라이언트도 다른 계정으로
	 * 로그인·가입할 수 있고, 정지 계정 자신으로 로그인하면 필터의 쓰기 오류가 아니라 로그인 계약(비밀번호가 맞으면 403 {@code suspended}
	 * "정지된 계정은 로그인할 수 없습니다", 틀리면 401)을 받는다. 단 휴대폰 검증의 연결 모드({@code ?link=true})는 지금 계정(정지)을 바꾸는
	 * 쓰기라 빼지 않는다 — 판정은 {@link #loginModeOnly} (연결 모드일 수 있는 표기는 모두 예외가 아니다).
	 */
	static final List<RequestMatcher> SUSPENDED_EXEMPT = Stream
		.concat(Stream.of(LOGOUT), LOGIN_FAMILY.stream()
			.map(matcher -> (RequestMatcher) request -> matcher.matches(request) && loginModeOnly(request)))
		.toList();

	/**
	 * 익명도 부를 수 있는 공개 GET (아래 인가 규칙의 permitAll). 새 공개 읽기 API 는 여기에 더한다.
	 * 게시판 읽기(TASK-8): 글 목록·상세·카테고리·태그, 댓글 목록(TASK-9#1). CSRF 토큰 발급, 헬스체크.
	 */
	static final List<RequestMatcher> PUBLIC_GETS = Stream
		.of("/auth/csrf", "/posts", "/posts/*", "/categories", "/tags", "/posts/*/comments", "/actuator/health",
				"/actuator/health/**")
		.<RequestMatcher>map(path -> PathPatternRequestMatcher.pathPattern(HttpMethod.GET, path))
		.toList();

	/**
	 * 만료된 Bearer 헤더를 무시하는 요청 (TASK-24#1, BE-15): refresh(새 토큰을 받으려는 것), 로그아웃(B1: refresh family 폐기),
	 * 공개 GET. {@link LenientBearerTokenResolver} 참고.
	 */
	static final RequestMatcher IGNORE_EXPIRED_BEARER = new OrRequestMatcher(
			Stream.concat(Stream.of(LOGOUT, PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/token/refresh")),
					PUBLIC_GETS.stream())
				.toList());

	/**
	 * 세션 로그인 뒤 status 재확인 (TASK-24#3). 세션이 저장된 뒤에 돌아야 하므로 Spring Session 필터({@code SessionRepositoryFilter})보다
	 * 바깥(앞 순서)에 둔다.
	 */
	@Bean
	FilterRegistrationBean<SessionLoginRecheckFilter> sessionLoginRecheckFilter(UserRepository users,
			UserSessions sessions) {
		FilterRegistrationBean<SessionLoginRecheckFilter> registration = new FilterRegistrationBean<>(
				new SessionLoginRecheckFilter(users, sessions));
		registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
		return registration;
	}

	/**
	 * 역직렬화할 수 없는 세션 무효화 (TASK-24#5). Spring Session 필터가 감싼 세션을 봐야 하므로 그 바로 뒤, 보안 체인보다 앞에 둔다.
	 */
	@Bean
	FilterRegistrationBean<CorruptSessionFilter> corruptSessionFilter() {
		FilterRegistrationBean<CorruptSessionFilter> registration = new FilterRegistrationBean<>(new CorruptSessionFilter());
		registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
		return registration;
	}

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
			CookieCsrfTokenRepository csrfTokenRepository, UserRepository users, OAuth2LoginCustomizer oauth2Login)
			throws Exception {
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
			.oauth2Login(oauth2Login)
			.oauth2ResourceServer(resourceServer -> resourceServer
				.bearerTokenResolver(new LenientBearerTokenResolver(IGNORE_EXPIRED_BEARER, tokens))
				.jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(tokens::toAuthentication))
				.authenticationEntryPoint(errors)
				.accessDeniedHandler(errors))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
			.authorizeHttpRequests(authorize -> authorize
				// 공개 엔드포인트. 새 공개 API(예: 게시글 목록, 로그인)는 여기에 명시적으로 더한다.
				.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
				// 공개 GET: CSRF 토큰, 게시판 읽기(TASK-8: 글 목록·상세·카테고리·태그는 익명도 본다), 댓글 목록(TASK-9#1).
				// 쓰기는 아래 기본 규칙(인증) + CSRF + 정지·step-up 필터.
				.requestMatchers(PUBLIC_GETS.toArray(RequestMatcher[]::new)).permitAll()
				.requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/login", "/auth/logout", "/auth/token/refresh")
					.permitAll()
				// 휴대폰 OTP 발송·검증 (TASK-7). 세션 경로는 CSRF 검사 대상이다.
				.requestMatchers(HttpMethod.POST, "/auth/phone/otp", "/auth/phone/verify").permitAll()
				.requestMatchers("/error").permitAll()
				// 관리자 API (TASK-12 B1): 체인에서 ADMIN 만. 익명은 401, 비ADMIN 은 403. 쓰기는 여전히 CSRF·정지·step-up 필터를 거친다.
				.requestMatchers("/admin/**").hasRole("ADMIN")
				// 기본은 인증 필요 (deny-by-default). 세부 인가는 @PreAuthorize.
				.anyRequest().authenticated())
			// principal 을 DB 최신 역할·상태로 (TASK-24#2, BE-16). 인가(AuthorizationFilter)와 아래 필터들이 최신 값을 본다.
			.addFilterBefore(new CurrentUserFilter(users), AuthorizationFilter.class)
			// SUSPENDED 쓰기 차단(B3). 여기서 던진 AccessDeniedException 도 ExceptionTranslationFilter 가 403 JSON 으로 바꾼다.
			.addFilterAfter(new SuspendedUserWriteFilter(SUSPENDED_EXEMPT), AuthorizationFilter.class)
			// 재활용 번호 보호(TASK-7 B4): 제한 세션·토큰의 쓰기 차단. 정지 판정 다음에 둔다(정지가 우선).
			.addFilterAfter(new StepUpRequiredWriteFilter(STEP_UP_EXEMPT), SuspendedUserWriteFilter.class);
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
	 * <p>{@code POST /auth/token/refresh}: 자격(refresh 토큰)이 쿠키가 아니라 본문에 있어 브라우저가 자동으로 붙일 수 없고,
	 * 공격자가 그 값을 모르면 위조 요청은 401 일 뿐이다. 응답(새 토큰)도 본문이라 다른 사이트가 읽을 수 없다. 세션 쿠키가
	 * 같이 와도 이 엔드포인트는 세션을 읽거나 바꾸지 않는다.
	 *
	 * <p>{@code POST /auth/phone/verify?token=jwt} (TASK-7): login?token=jwt 와 같은 이유. 단 {@code link=true} 가 붙으면 현재
	 * 로그인(세션 쿠키)에 identity 를 붙이는 상태 변경이라 면제하지 않는다. (컨트롤러도 link 와 token 을 같이 받지 않는다.)
	 */
	static RequestMatcher csrfExempt() {
		RequestMatcher login = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/login");
		RequestMatcher refresh = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/token/refresh");
		RequestMatcher phoneVerify = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/phone/verify");
		return request -> refresh.matches(request)
				|| (login.matches(request) && "jwt".equals(queryParam(request, "token")))
				|| (phoneVerify.matches(request) && "jwt".equals(queryParam(request, "token"))
						&& loginModeOnly(request));
	}

	/**
	 * 휴대폰 검증이 연결 모드가 <em>아님이 확실한</em> 요청인가 (TASK-24#4): {@code link} 파라미터가 없거나, 모든 값이 앞뒤 공백을 뺀
	 * {@code false}(대소문자 무시)일 때만 참이다. 컨트롤러의 {@code @RequestParam boolean link} 는 서블릿이 디코딩한 값(쿼리·폼, 예:
	 * {@code %74rue}, {@code +true})을 Spring 변환으로 읽어 {@code true}·{@code on}·{@code yes}·{@code 1} 을 모두 참으로 받으므로,
	 * 원문 쿼리를 {@code "true"} 와만 비교하면 다른 표기로 연결 모드가 예외를 통과한다. 그래서 같은 디코딩 값을 보고, 거짓이라고 확신할 수
	 * 없는 값(빈 값·알 수 없는 값 포함 — 컨트롤러는 400 으로 거절한다)은 연결 모드로 친다.
	 */
	static boolean loginModeOnly(HttpServletRequest request) {
		String[] values = request.getParameterValues("link");
		if (values == null) {
			return true;
		}
		return values.length > 0 && Stream.of(values).allMatch(value -> "false".equalsIgnoreCase(value.strip()));
	}

	private static String queryParam(HttpServletRequest request, String name) {
		String query = request.getQueryString();
		if (query == null) {
			return null;
		}
		return UriComponentsBuilder.newInstance().query(query).build().getQueryParams().getFirst(name);
	}

}
