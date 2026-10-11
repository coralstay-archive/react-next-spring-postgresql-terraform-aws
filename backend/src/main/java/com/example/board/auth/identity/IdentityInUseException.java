package com.example.board.auth.identity;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** 이미 다른 계정에 연결된 identity 를 연결하려 했다 (TASK-4 B2). JSON 으로 나가면 409. */
public class IdentityInUseException extends ApiException {

	private static final long serialVersionUID = 1L;

	public static final String CODE = "identity_in_use";

	public IdentityInUseException() {
		super(HttpStatus.CONFLICT, CODE, "이미 다른 계정에 연결된 로그인 수단입니다");
	}

}
