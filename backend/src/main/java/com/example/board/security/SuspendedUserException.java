package com.example.board.security;

import org.springframework.security.access.AccessDeniedException;

/** SUSPENDED 계정의 쓰기 시도 (TASK-2 B3). 403 JSON {@code error=suspended} 로 응답한다. */
public class SuspendedUserException extends AccessDeniedException {

	private static final long serialVersionUID = 1L;

	public SuspendedUserException() {
		super("정지된 계정은 쓰기 요청을 할 수 없습니다");
	}

}
