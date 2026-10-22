package com.example.board.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.board.auth.AuthUser;
import com.example.board.auth.AuthUserAuthentication;
import com.example.board.user.UserRepository;

/**
 * 인증된 요청의 principal 을 DB 의 최신 역할·상태로 바꾼다 (TASK-24#2, BE-16).
 *
 * <p>세션·access JWT 에 실린 {@link AuthUser} 의 role/status 는 발급 시점 값이다. 강등된 ADMIN 이 세션 수명(14일)·토큰 수명 동안
 * 관리자 API 를 쓰지 못하게, 요청마다 PK 조회 한 번으로 최신 값을 읽어 이 요청의 SecurityContext 에만 넣는다(세션에는 다시 쓰지 않는다).
 * 역할 변경 API 가 없어 "변경 시 세션 삭제"를 걸 곳이 없고 JWT 는 지울 수도 없으므로, 정지 판정({@link SuspendedUserWriteFilter})과 같은
 * "요청마다 DB 대조" 방식이다. {@code stepUpRequired} 는 세션·토큰의 성질이라 그대로 둔다.
 *
 * <p>보안 체인에서 인증 필터들 뒤·{@code AuthorizationFilter} 앞에 있어 URL 규칙({@code /admin/**})·{@code @PreAuthorize}·
 * {@link OwnershipChecker}·컨트롤러의 {@code @AuthenticationPrincipal} 이 모두 최신 값을 본다. 계정이 지워졌으면 컨텍스트를 비운다
 * (보호된 API 는 401, 공개 API 는 익명).
 */
public class CurrentUserFilter extends OncePerRequestFilter {

	private final UserRepository users;

	private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

	public CurrentUserFilter(UserRepository users) {
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = holder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
			SecurityContext refreshed = holder.createEmptyContext();
			users.findRoleAndStatusById(user.id())
				.ifPresent(current -> refreshed.setAuthentication(AuthUserAuthentication
					.of(new AuthUser(user.id(), current.role(), current.status(), user.stepUpRequired()))));
			holder.setContext(refreshed);
		}
		chain.doFilter(request, response);
	}

}
