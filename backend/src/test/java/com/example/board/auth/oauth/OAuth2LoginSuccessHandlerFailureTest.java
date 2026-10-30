package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.board.auth.AuthUser;
import com.example.board.auth.AuthUserAuthentication;
import com.example.board.auth.SessionLogin;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.user.User;
import com.example.board.user.UserRepository;

/**
 * TASK-25#1 TASK-25#B1 성공 핸들러 실패 처리의 경계: 연결 플래그({@link LinkIntent}) 를 꺼내다 예외가 나면 모드를 알 수 없다 — 그래도 로그인
 * 콜백으로 302 하고({@code server_error}), 이번 요청이 세션 로그인을 하지 않았으므로 세션의 기존 인증(연결하려던 로그인 사용자)을 걷어 내지 않는다.
 */
class OAuth2LoginSuccessHandlerFailureTest {

	static final String LOGIN_FAILED = "http://board.test/auth/callback/?error=server_error";

	IdentityResolver resolver;

	OAuth2Redirects redirects;

	OAuth2LoginSuccessHandler handler;

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		resolver = mock(IdentityResolver.class);
		redirects = mock(OAuth2Redirects.class);
		when(redirects.loginFailed(OAuth2LoginSuccessHandler.SERVER_ERROR)).thenReturn(LOGIN_FAILED);
		when(redirects.linkFailed(OAuth2LoginSuccessHandler.SERVER_ERROR))
			.thenReturn("http://board.test/account/?error=server_error");
		SessionLogin sessionLogin = new SessionLogin(new HttpSessionSecurityContextRepository(),
				new HttpSessionCsrfTokenRepository());
		handler = new OAuth2LoginSuccessHandler(List.of(), resolver, sessionLogin, redirects, mock(UserRepository.class),
				Clock.systemUTC());
	}

	@Test
	@DisplayName("TASK-25#1 TASK-25#B1 연결 플래그를 꺼내다 실패하면 로그인 콜백 server_error 로 302 하고 세션의 기존 인증은 남긴다")
	void linkIntentReadFailureKeepsSessionAuthentication() throws Exception {
		MockHttpSession session = new MockHttpSession() {
			@Override
			public Object getAttribute(String name) {
				if (LinkIntent.SESSION_ATTRIBUTE.equals(name)) {
					throw new IllegalStateException("세션 속성 역직렬화 실패");
				}
				return super.getAttribute(name);
			}
		};
		User user = User.create("linker");
		ReflectionTestUtils.setField(user, "id", 7L);
		SecurityContextImpl existing = new SecurityContextImpl(AuthUserAuthentication.of(AuthUser.from(user)));
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, existing);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/login/oauth2/code/google");
		request.setSession(session);
		MockHttpServletResponse response = new MockHttpServletResponse();
		OAuth2AuthenticationToken token = new OAuth2AuthenticationToken(
				new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("sub", "g-1"), "sub"),
				AuthorityUtils.NO_AUTHORITIES, "google");

		handler.onAuthenticationSuccess(request, response, token);

		assertThat(response.getRedirectedUrl()).isEqualTo(LOGIN_FAILED);
		assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
			.as("이번 요청이 로그인하지 않았으면 기존 인증을 걷어 내지 않는다")
			.isSameAs(existing);
		verifyNoInteractions(resolver);
	}

}
