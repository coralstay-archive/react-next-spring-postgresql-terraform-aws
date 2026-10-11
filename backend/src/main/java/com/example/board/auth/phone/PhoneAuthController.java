package com.example.board.auth.phone;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.security.ClientIpResolver;

/** 휴대폰 OTP 로그인 API ({@code /api/auth/phone/*}, TASK-7). 공개 엔드포인트. 세션 경로는 CSRF 검사 대상이다. */
@RestController
public class PhoneAuthController {

	private final PhoneOtpService otp;

	private final ClientIpResolver clientIp;

	public PhoneAuthController(PhoneOtpService otp, ClientIpResolver clientIp) {
		this.otp = otp;
		this.clientIp = clientIp;
	}

	/** 인증번호 발송. 202 {@code {challengeId, expiresIn}}. 계정이 있든 없든 같은 응답이다. */
	@PostMapping("/auth/phone/otp")
	public ResponseEntity<OtpChallengeResponse> otp(@Valid @RequestBody PhoneOtpRequest body,
			HttpServletRequest request) {
		return ResponseEntity.accepted().body(otp.send(body.phone(), clientIp.resolve(request)));
	}

}
