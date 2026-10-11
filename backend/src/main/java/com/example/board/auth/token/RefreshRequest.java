package com.example.board.auth.token;

/** refresh 토큰 원문을 담는 본문 ({@code /auth/token/refresh}, Bearer 로그아웃). */
public record RefreshRequest(String refreshToken) {
}
