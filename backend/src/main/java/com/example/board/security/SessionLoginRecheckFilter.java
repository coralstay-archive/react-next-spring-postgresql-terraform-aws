package com.example.board.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.board.auth.SessionLogin;
import com.example.board.auth.UserSessions;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * 세션 로그인과 정지의 경쟁 (TASK-24#3, BE-17): 세션이 저장된 <em>뒤</em> 사용자 status 를 다시 보고, ACTIVE 가 아니면 그 사용자의 세션을
 * 모두 지운다.
 *
 * <p>로그인은 status 를 확인한 다음 세션을 만들지만, 세션 행은 요청 끝에 Spring Session 필터가 별도 트랜잭션으로 저장한다. 그 사이에
 * 정지(status 커밋 → 그 사용자 세션 삭제)가 끝까지 지나가면, 아직 저장되지 않은 이 세션은 삭제를 피해 살아남는다. 그래서 이 필터는
 * Spring Session 필터보다 <em>바깥</em>에 있어(체인이 돌아오면 세션은 이미 저장됐다), {@link SessionLogin} 이 남긴 요청 속성으로 이번
 * 요청이 세션 로그인을 했는지 보고 status 를 다시 읽는다. 어떤 끼어듦이든 세션이 남지 않는다: 재확인이 ACTIVE 를 봤다면 정지는 아직
 * 커밋 전이고, 정지의 세션 삭제는 그 뒤에 일어나 이미 저장된 이 세션을 찾는다. 재확인이 정지를 보면 여기서 지운다.
 *
 * <p>로그인 응답 자체는 200 일 수 있지만 그 세션은 다음 요청부터 없다(익명, 보호된 API 401). LOCAL·OTP·소셜·step-up 세션 로그인이 모두
 * {@link SessionLogin} 을 거치므로 같이 적용된다.
 */
public class SessionLoginRecheckFilter extends OncePerRequestFilter {

	private static final Log log = LogFactory.getLog(SessionLoginRecheckFilter.class);

	private final UserRepository users;

	private final UserSessions sessions;

	public SessionLoginRecheckFilter(UserRepository users, UserSessions sessions) {
		this.users = users;
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		finally {
			if (request.getAttribute(SessionLogin.LOGGED_IN_USER_ID) instanceof Long userId
					&& !users.findStatusById(userId).map(UserStatus.ACTIVE::equals).orElse(false)) {
				int deleted = sessions.deleteAll(userId);
				log.info("로그인과 동시에 정지된 사용자의 세션 삭제: user=" + userId + " 세션=" + deleted);
			}
		}
	}

}
