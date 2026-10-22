package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.UserSessions;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * TASK-24#3 세션 로그인 뒤 status 재확인이 실패해도(DB 오류) 세션을 남기지 않는 쪽으로 — 그 사용자의 세션 삭제를 시도하고, 체인이 던진 원래
 * 예외를 가리지 않는다.
 */
class SessionLoginRecheckFilterTest {

	private static final long USER_ID = 42L;

	UserRepository users;

	UserSessions sessions;

	SessionLoginRecheckFilter filter;

	MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		sessions = mock(UserSessions.class);
		filter = new SessionLoginRecheckFilter(users, sessions);
		request = new MockHttpServletRequest("POST", "/api/auth/login");
	}

	private FilterChain loggingIn() {
		return (req, res) -> req.setAttribute(SessionLogin.LOGGED_IN_USER_ID, USER_ID);
	}

	@Test
	@DisplayName("TASK-24#3 재확인 DB 조회가 실패하면 그 사용자의 세션 삭제를 시도하고 요청은 그대로 끝난다")
	void recheckFailureDeletesSessions() {
		when(users.findStatusById(USER_ID)).thenThrow(new DataAccessResourceFailureException("db down"));

		assertThatCode(() -> filter.doFilter(request, new MockHttpServletResponse(), loggingIn()))
			.doesNotThrowAnyException();

		verify(sessions).deleteAll(USER_ID);
	}

	@Test
	@DisplayName("TASK-24#3 체인이 예외를 던지고 재확인도 실패하면 원래 예외가 나가고(재확인 오류는 suppressed) 세션 삭제를 시도한다")
	void recheckFailureDoesNotMaskOriginalException() {
		ServletException original = new ServletException("handler failed");
		DataAccessResourceFailureException recheck = new DataAccessResourceFailureException("db down");
		when(users.findStatusById(USER_ID)).thenThrow(recheck);
		FilterChain failing = (req, res) -> {
			req.setAttribute(SessionLogin.LOGGED_IN_USER_ID, USER_ID);
			throw original;
		};

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing)).isSameAs(original);
		assertThat(original.getSuppressed()).containsExactly(recheck);
		verify(sessions).deleteAll(USER_ID);
	}

	@Test
	@DisplayName("TASK-24#3 세션 삭제까지 실패해도 원래 흐름을 깨지 않는다(로그만)")
	void deleteFailureIsLogged() {
		when(users.findStatusById(USER_ID)).thenThrow(new DataAccessResourceFailureException("db down"));
		when(sessions.deleteAll(USER_ID)).thenThrow(new DataAccessResourceFailureException("still down"));

		assertThatCode(() -> filter.doFilter(request, new MockHttpServletResponse(), loggingIn()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("TASK-24#3 ACTIVE 면 세션을 지우지 않고, 정지면 지운다")
	void activeKeepsSuspendedDeletes() throws Exception {
		when(users.findStatusById(USER_ID)).thenReturn(Optional.of(UserStatus.ACTIVE));
		filter.doFilter(request, new MockHttpServletResponse(), loggingIn());
		verify(sessions, never()).deleteAll(USER_ID);

		when(users.findStatusById(USER_ID)).thenReturn(Optional.of(UserStatus.SUSPENDED));
		filter.doFilter(new MockHttpServletRequest("POST", "/api/auth/login"), new MockHttpServletResponse(),
				loggingIn());
		verify(sessions).deleteAll(USER_ID);
	}

}
