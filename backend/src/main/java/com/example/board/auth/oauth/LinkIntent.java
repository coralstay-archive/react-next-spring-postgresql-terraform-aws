package com.example.board.auth.oauth;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import com.example.board.auth.AuthUser;

/**
 * 계정 연결 모드 플래그 (doc-1: "연결 모드는 세션 플래그로 구분한다"). 세션 속성으로 저장된다(Spring Session JDBC).
 *
 * <ul>
 * <li>로그인한 사용자가 {@code GET /api/me/identities/link/{p}} 로 시작할 때만 만든다. 시작한 사용자 id 와 제공자에 묶이고
 * TTL({@code app.oauth2.link-intent-ttl}, 기본 10분)이 짧다.</li>
 * <li>콜백에서 한 번 꺼내면 지운다(성공·실패 모두). 콜백 시점의 세션 사용자가 시작한 사용자와 같고, 같은 제공자이고, 만료 전일
 * 때만 연결한다 — 그 사이 세션이 다른 계정으로 바뀌었으면 연결하지 않는다.</li>
 * <li>연결 플래그가 남은 채 일반 로그인({@code /api/oauth2/authorization/{p}}, {@code ?link=true} 없음)을 시작하면 버린다
 * ({@link LinkAwareAuthorizationRequestResolver}). 그래서 "연결하려다 그만두고 나중에 그냥 로그인"이 연결로 처리되지 않는다.</li>
 * </ul>
 * 연결 시작이 GET 이라 다른 사이트가 시작시킬 수는 있지만, 콜백은 OAuth state(세션에 저장)로 보호되고 붙는 identity 는 피해자
 * 브라우저에서 피해자가 제공자에 로그인해 동의한 계정뿐이다.
 */
public record LinkIntent(Long userId, String registrationId, Instant expiresAt) implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final String SESSION_ATTRIBUTE = LinkIntent.class.getName();

	public LinkIntent {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(registrationId, "registrationId");
		Objects.requireNonNull(expiresAt, "expiresAt");
	}

	public boolean isValidFor(AuthUser current, String callbackRegistrationId, Instant now) {
		return current != null && userId.equals(current.id()) && registrationId.equals(callbackRegistrationId)
				&& now.isBefore(expiresAt);
	}

	public static void start(HttpServletRequest request, LinkIntent intent) {
		request.getSession(true).setAttribute(SESSION_ATTRIBUTE, intent);
	}

	/** 꺼내고 지운다. 없으면 null. */
	public static LinkIntent take(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return null;
		}
		Object value = session.getAttribute(SESSION_ATTRIBUTE);
		if (value != null) {
			session.removeAttribute(SESSION_ATTRIBUTE);
		}
		return value instanceof LinkIntent intent ? intent : null;
	}

	public static void discard(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null && session.getAttribute(SESSION_ATTRIBUTE) != null) {
			session.removeAttribute(SESSION_ATTRIBUTE);
		}
	}

}
