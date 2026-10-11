package com.example.board.auth.phone;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/phone/otp} 본문. 형식 검사(E.164 정규화)는 서비스가 한다(400 invalid_phone). */
public record PhoneOtpRequest(@NotBlank(message = "휴대폰 번호를 입력하세요") String phone) {
}
