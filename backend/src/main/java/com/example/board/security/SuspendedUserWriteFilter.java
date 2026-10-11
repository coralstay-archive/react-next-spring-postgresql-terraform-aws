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
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * SUSPENDED 계정의 모든 쓰기 요청(POST/PUT/PATCH/DELETE)을 403 으로 막는다 (TASK-2 B3). 읽기는 막지 않는다.
 *
 * <p>보안 체인의 {@code AuthorizationFilter} 바로 뒤에 붙어 URL 규칙·컨트롤러·{@code @PreAuthorize} 와 상관없이 한곳에서 적용된다.
 * 세션·토큰에 실린 status 는 발급 시점 값이라 낡을 수 있으므로, 쓰기 요청마다 DB 의 최신 status 를 본다
 * (계정이 없어졌으면 역시 403). 정지된 사용자도 해야 하는 쓰기(예: TASK-3 의 로그아웃)는 {@code exemptions} 로 뺀다.
 */
public class SuspendedUserWriteFilter extends OncePerRequestFilter {

	private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private final UserRepository users;

	private final List<RequestMatcher> exemptions;

	public SuspendedUserWriteFilter(UserRepository users, List<RequestMatcher> exemptions) {
		this.users = users;
		this.exemptions = List.copyOf(exemptions);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (isWrite(request) && !isExempt(request)) {
			Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
			if (authentication != null && authentication.getPrincipal() instanceof AuthUser user && !isActive(user)) {
				throw new SuspendedUserException();
			}
		}
		chain.doFilter(request, response);
	}

	private boolean isActive(AuthUser user) {
		if (user.isSuspended()) {
			return false;
		}
		return users.findStatusById(user.id()).map(UserStatus.ACTIVE::equals).orElse(false);
	}

	private static boolean isWrite(HttpServletRequest request) {
		return WRITE_METHODS.contains(request.getMethod());
	}

	private boolean isExempt(HttpServletRequest request) {
		return exemptions.stream().anyMatch(matcher -> matcher.matches(request));
	}

}
