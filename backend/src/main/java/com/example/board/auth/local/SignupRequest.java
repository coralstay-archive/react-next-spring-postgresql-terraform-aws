package com.example.board.auth.local;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/auth/signup} 본문. 이메일·닉네임은 앞뒤 공백을 떼고 검증한다(비밀번호는 그대로).
 */
public record SignupRequest(
		@NotBlank(message = "이메일을 입력하세요") @Size(max = 320, message = "이메일이 너무 깁니다")
		@Email(regexp = Emails.SHAPE, message = "이메일 형식이 아닙니다") String email,
		@PasswordPolicy String password,
		@NotBlank(message = "닉네임을 입력하세요")
		@Size(min = 2, max = 20, message = "닉네임은 2~20자입니다") String nickname) {

	public SignupRequest {
		email = email == null ? null : email.strip();
		nickname = nickname == null ? null : nickname.strip();
	}

}
