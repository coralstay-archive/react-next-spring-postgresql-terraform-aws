package com.example.board.auth.local;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/login} 본문. 형식 검사는 빈 값만 한다 — 정책 위반 여부를 따로 알려 주면 B2(계정 존재 비노출)와 무관하게
 * 정책 정보만 새므로, 나머지는 모두 "이메일 또는 비밀번호가 올바르지 않음" 401 로 처리한다.
 */
public record LoginRequest(@NotBlank(message = "이메일을 입력하세요") String email,
		@NotBlank(message = "비밀번호를 입력하세요") String password) {
}
