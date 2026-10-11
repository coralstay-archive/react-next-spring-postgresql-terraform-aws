package com.example.board.auth.oauth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.example.board.auth.AuthUser;
import com.example.board.auth.SessionLogin;
import com.example.board.auth.identity.IdentityInUseException;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.security.StepUpRequiredException;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
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
 *
 * <p>휴대폰 로그인의 제한 세션(TASK-7 B4)에서 로그인 모드로 들어오면, 찾은 계정으로 {@link SessionLogin} 이 제한 없는 새 세션을
 * 만든다 — 같은 계정의 소셜·Cognito identity 로 로그인하는 것이 곧 step-up 이다.
 *
 * <p><b>연결 모드</b>(세션에 {@link LinkIntent}): 로그인하지 않고, 세션의 현재 사용자(로그인 필터가 세션의 SecurityContext 를
 * 건드리지 않으므로 그대로 남아 있다)에게 identity 를 붙인 뒤 계정 화면으로 보낸다. 플래그가 그 사용자·제공자의 것이 아니거나
 * 만료됐으면 {@code link_expired}, 이미 다른 계정의 identity 면 {@code identity_in_use}(B2), 정지 계정이면 {@code suspended}.
 */
public final class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

	public static final String SUSPENDED = "suspended";

	public static final String INVALID_USER_INFO = "invalid_user_info";

	public static final String LINK_EXPIRED = "link_expired";

	private final Map<String, OAuth2UserInfoMapper> mappers;

	private final IdentityResolver resolver;

	private final SessionLogin sessionLogin;

	private final OAuth2Redirects redirects;

	private final UserRepository users;

	public OAuth2LoginSuccessHandler(List<OAuth2UserInfoMapper> mappers, IdentityResolver resolver,
			SessionLogin sessionLogin, OAuth2Redirects redirects, UserRepository users) {
		this.mappers = mappers.stream()
			.collect(Collectors.toUnmodifiableMap(OAuth2UserInfoMapper::registrationId, Function.identity()));
		this.resolver = resolver;
		this.sessionLogin = sessionLogin;
		this.redirects = redirects;
		this.users = users;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// 스프링의 OAuth2 인증은 이 요청 안에서도 쓰지 않는다.
		SecurityContextHolder.clearContext();
		OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
		OAuth2UserInfo info = userInfo(token);
		LinkIntent intent = LinkIntent.take(request);
		if (intent != null) {
			response.sendRedirect(link(intent, token.getAuthorizedClientRegistrationId(), info, request));
			return;
		}
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

	/** 연결 모드 처리 후 돌려보낼 URL. */
	private String link(LinkIntent intent, String registrationId, OAuth2UserInfo info, HttpServletRequest request) {
		AuthUser current = sessionUser(request);
		if (!intent.isValidFor(current, registrationId, Instant.now())) {
			return redirects.linkFailed(LINK_EXPIRED);
		}
		if (current.stepUpRequired()) {
			// 제한 세션(TASK-7 B4)은 수단을 연결할 수 없다. (연결 시작에서 이미 막지만 콜백에서도 확인한다.)
			return redirects.linkFailed(StepUpRequiredException.CODE);
		}
		UserStatus status = users.findStatusById(current.id()).orElse(null);
		if (status == null) {
			return redirects.linkFailed(LINK_EXPIRED);
		}
		if (status != UserStatus.ACTIVE) {
			return redirects.linkFailed(SUSPENDED);
		}
		if (info == null) {
			return redirects.linkFailed(INVALID_USER_INFO);
		}
		try {
			resolver.link(current.id(), info);
		}
		catch (IdentityInUseException | DataIntegrityViolationException ex) {
			// 동시에 다른 계정이 같은 identity 를 가져간 경우(UNIQUE 위반)도 B2 와 같다
			return redirects.linkFailed(IdentityInUseException.CODE);
		}
		return redirects.linked(registrationId);
	}

	/** 세션에 저장된 현재 사용자 (이 요청의 SecurityContextHolder 는 스프링의 OAuth2 인증으로 바뀌었으므로 세션을 직접 본다). */
	private static AuthUser sessionUser(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		if (value instanceof SecurityContext context && context.getAuthentication() != null
				&& context.getAuthentication().getPrincipal() instanceof AuthUser user) {
			return user;
		}
		return null;
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
