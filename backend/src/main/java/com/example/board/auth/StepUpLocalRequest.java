package com.example.board.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/step-up/local} 본문. Bearer 클라이언트는 제한된 refresh 토큰을 같이 보내면 그 family 를 폐기한다(선택).
 */
public record StepUpLocalRequest(@NotBlank(message = "비밀번호를 입력하세요") String password, String refreshToken) {
}
