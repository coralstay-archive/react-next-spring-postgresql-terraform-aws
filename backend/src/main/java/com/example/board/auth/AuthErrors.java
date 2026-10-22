package com.example.board.auth;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** 로그인 계열 엔드포인트가 같이 쓰는 오류 (docs/api.md 인증 절). */
public final class AuthErrors {

	public static final String SUSPENDED = "suspended";

	private AuthErrors() {
	}

	/** 정지 계정의 로그인(비밀번호·OTP 가 맞았을 때, 로그인과 동시에 정지된 경우 포함 — TASK-24#3): 403 {@code suspended}. */
	public static ApiException suspendedLogin() {
		return new ApiException(HttpStatus.FORBIDDEN, SUSPENDED, "정지된 계정은 로그인할 수 없습니다");
	}

	/**
	 * 정지 계정이 지금 계정을 바꾸는 쓰기(휴대폰 연결 등): 403 {@code suspended}. 정지 쓰기 차단 필터({@code SuspendedUserException})와 같은
	 * 문구 — 필터를 지나온 경우의 심층 방어(TASK-24#4).
	 */
	public static ApiException suspendedWrite() {
		return new ApiException(HttpStatus.FORBIDDEN, SUSPENDED, "정지된 계정은 쓰기 요청을 할 수 없습니다");
	}

}
