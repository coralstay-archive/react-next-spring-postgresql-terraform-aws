package com.example.board.security;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.board.auth.AuthUser;

/**
 * 재활용 번호 보호 (TASK-7 B4): 제한 세션·토큰({@link AuthUser#stepUpRequired()})의 쓰기 요청(POST/PUT/PATCH/DELETE)을 403
 * {@code step_up_required} 로 막는다. 읽기는 막지 않는다. {@link SuspendedUserWriteFilter} 처럼 보안 체인에서 한곳에 적용된다.
 *
 * <p>예외(exemptions): 로그아웃, step-up, 그리고 로그인 계열(LOCAL 로그인·가입·refresh·휴대폰 발송/검증) — 다른 수단으로 다시
 * 로그인하는 것 자체가 step-up 이기 때문이다. 휴대폰 검증의 연결 모드({@code ?link=true})는 컨트롤러가 따로 막는다. 쓰기가 아닌
 * 계정 연결 시작(GET)·OAuth 콜백의 연결 모드도 각자 막는다.
 */
public class StepUpRequiredWriteFilter extends OncePerRequestFilter {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final List<RequestMatcher> exemptions;

	public StepUpRequiredWriteFilter(List<RequestMatcher> exemptions) {
		this.exemptions = List.copyOf(exemptions);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (WRITE_METHODS.contains(request.getMethod()) && exemptions.stream().noneMatch(m -> m.matches(request))) {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication != null && authentication.getPrincipal() instanceof AuthUser user
					&& user.stepUpRequired()) {
				throw new StepUpRequiredException();
			}
		}
		chain.doFilter(request, response);
	}

}
