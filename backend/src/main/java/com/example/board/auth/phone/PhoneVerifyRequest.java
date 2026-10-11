package com.example.board.auth.phone;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/phone/verify} 본문. challengeId 형식이 틀리면 없는 챌린지와 같은 401 이다. */
public record PhoneVerifyRequest(@NotBlank(message = "challengeId 를 입력하세요") String challengeId,
		@NotBlank(message = "인증번호를 입력하세요") String code) {
}
