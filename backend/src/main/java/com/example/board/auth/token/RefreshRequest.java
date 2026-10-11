package com.example.board.auth.token;

import jakarta.validation.constraints.NotBlank;

/** refresh 토큰 원문을 담는 본문 ({@code /auth/token/refresh} 에서는 필수, Bearer 로그아웃에서는 선택). */
public record RefreshRequest(@NotBlank(message = "refreshToken 을 입력하세요") String refreshToken) {
}
