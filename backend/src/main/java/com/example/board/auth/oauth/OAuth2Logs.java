package com.example.board.auth.oauth;

import java.util.StringJoiner;

/**
 * OAuth 흐름 로그용 원인 요약 (TASK-25#1·#2). 예외 <em>메시지</em>는 싣지 않는다 — 스프링·제공자 예외 메시지에는 제공자 원문 응답 본문,
 * error_description, 이메일 같은 값이 들어갈 수 있다. 클래스 이름 사슬만 남긴다(예: {@code OAuth2AuthenticationException <-
 * OAuth2AuthorizationException <- ResourceAccessException <- HttpTimeoutException}).
 */
final class OAuth2Logs {

	private static final int MAX_DEPTH = 8;

	private OAuth2Logs() {
	}

	static String causes(Throwable exception) {
		StringJoiner chain = new StringJoiner(" <- ");
		int depth = 0;
		for (Throwable t = exception; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
			chain.add(t.getClass().getSimpleName());
			if (t.getCause() == t) {
				break;
			}
		}
		return chain.toString();
	}

}
