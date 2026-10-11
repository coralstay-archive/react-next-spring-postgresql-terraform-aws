package com.example.board.auth.phone;

import java.util.UUID;

/** {@code POST /api/auth/phone/otp} 응답 (202). 계정 존재 여부는 싣지 않는다. expiresIn 은 초. */
public record OtpChallengeResponse(UUID challengeId, long expiresIn) {
}
