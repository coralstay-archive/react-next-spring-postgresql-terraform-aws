package com.example.board.auth;

import java.util.Map;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPA 가 처음 로드될 때 호출해 {@code XSRF-TOKEN} 쿠키를 받는다. 이후 상태 변경 요청에는 그 쿠키 값을
 * {@code X-XSRF-TOKEN} 헤더로 보낸다. 토큰 원문은 응답 본문에 싣지 않는다(쿠키가 원본이다).
 */
@RestController
public class CsrfController {

	@GetMapping("/auth/csrf")
	public Map<String, String> csrf(CsrfToken token) {
		// 지연 로딩된 토큰을 꺼내야 쿠키가 응답에 실린다.
		token.getToken();
		return Map.of("headerName", token.getHeaderName(), "cookieName", "XSRF-TOKEN");
	}

}
