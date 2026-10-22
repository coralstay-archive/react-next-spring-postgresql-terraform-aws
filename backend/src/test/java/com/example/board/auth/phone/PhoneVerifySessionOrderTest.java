package com.example.board.auth.phone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.token.TokenService;
import com.example.board.security.ClientIpResolver;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/**
 * TASK-25#5 BE-22 리뷰 반영: 세션 로그인 뒤 실패로 "오류 응답 + 로그인된 세션 + 다시 쓸 수 있는 OTP"(OTP 하나로 로그인 두 번)가 되지 않게 한다.
 *
 * <p>불변식: <b>오류 응답이면 로그인되지 않았고 OTP 는 다시 쓸 수 있다.</b> 세션 로그인을 계정 처리의 마지막 단계로 두고(응답 본문에 쓸 수단 목록을
 * 먼저 조회), 세션 로그인 도중에 실패하면 이 요청이 세션에 넣은 인증을 걷어 낸 뒤 챌린지 소모를 되돌린다. 실제 세션 저장소·CSRF 저장소(세션 기반)를
 * 쓰는 {@link SessionLogin} 으로 확인한다.
 */
class PhoneVerifySessionOrderTest {

	private static final long USER_ID = 42L;

	private static final String E164 = "+821012345678";

	PhoneOtpService otp;

	PhoneIdentityService phoneIdentities;

	UserIdentityRepository identities;

	CsrfTokenRepository csrfTokens;

	PhoneAuthController controller;

	MockHttpServletRequest request;

	MockHttpServletResponse response;

	MockHttpSession existingSession;

	UUID challengeId;

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		otp = mock(PhoneOtpService.class);
		phoneIdentities = mock(PhoneIdentityService.class);
		identities = mock(UserIdentityRepository.class);
		csrfTokens = new HttpSessionCsrfTokenRepository();
		challengeId = UUID.randomUUID();
		User user = User.create("phone-user");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		when(otp.verify(eq(challengeId), any())).thenReturn(Optional.of(E164));
		when(phoneIdentities.resolveForLogin(E164)).thenReturn(user);
		request = new MockHttpServletRequest("POST", "/api/auth/phone/verify");
		existingSession = new MockHttpSession();
		request.setSession(existingSession);
		response = new MockHttpServletResponse();
	}

	private PhoneAuthController controller(CsrfTokenRepository csrf) {
		SessionLogin sessionLogin = new SessionLogin(new HttpSessionSecurityContextRepository(), csrf);
		return new PhoneAuthController(otp, phoneIdentities, mock(ClientIpResolver.class), sessionLogin,
				mock(TokenService.class), mock(UserRepository.class), identities);
	}

	private Object callVerify(PhoneAuthController controller) {
		return controller.verify(new PhoneVerifyRequest(challengeId.toString(), "123456"), null, false, null, request,
				response);
	}

	private void assertNotLoggedIn() {
		Object context = request.getSession(false) == null ? null
				: request.getSession(false)
					.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		assertThat(context).as("오류 응답인데 세션에 인증이 남으면 안 된다").isNull();
		assertThat(request.getAttribute(SessionLogin.LOGGED_IN_USER_ID)).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	@DisplayName("TASK-25#5 응답용 수단 목록 조회(DB)가 실패하면 오류이고 세션 로그인은 일어나지 않으며 챌린지 소모를 되돌린다")
	void providersLookupFailureDoesNotLogIn() {
		DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
		when(identities.existsByUserIdAndProviderNot(USER_ID, AuthProvider.PHONE)).thenReturn(false);
		when(identities.findProvidersByUserId(USER_ID)).thenThrow(failure);

		assertThatThrownBy(() -> callVerify(controller(csrfTokens))).isSameAs(failure);

		assertNotLoggedIn();
		assertThat(existingSession.isInvalid()).isFalse();
		assertThat(request.getSession(false).getId()).as("로그인하지 않았으니 세션 ID 도 그대로").isEqualTo(existingSession.getId());
		verify(otp).release(challengeId, failure);
	}

	@Test
	@DisplayName("TASK-25#5 세션 로그인 도중(인증 저장 뒤 CSRF 토큰 교체) 실패하면 이 요청이 넣은 인증을 걷어 내고 챌린지 소모를 되돌린다")
	void failureInsideSessionLoginDiscardsAuthentication() {
		when(identities.findProvidersByUserId(USER_ID)).thenReturn(List.of(AuthProvider.PHONE));
		CsrfTokenRepository failingCsrf = mock(CsrfTokenRepository.class);
		when(failingCsrf.generateToken(any())).thenAnswer(inv -> csrfTokens.generateToken(request));
		IllegalStateException failure = new IllegalStateException("csrf store down");
		doThrow(failure).when(failingCsrf).saveToken(any(), any(), any());

		assertThatThrownBy(() -> callVerify(controller(failingCsrf))).isSameAs(failure);

		assertNotLoggedIn();
		verify(otp).release(challengeId, failure);
	}

}
