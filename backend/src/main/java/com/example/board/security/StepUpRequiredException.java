package com.example.board.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.example.board.common.ApiException;

/** 제한 세션·토큰의 쓰기 시도 (TASK-7 B4). 403 JSON {@code error=step_up_required} 로 응답한다. */
public class StepUpRequiredException extends AccessDeniedException {

	private static final long serialVersionUID = 1L;

	public static final String CODE = "step_up_required";

	public static final String MESSAGE = "기존 로그인 수단으로 다시 인증해야 합니다";

	public StepUpRequiredException() {
		super(MESSAGE);
	}

	/** 컨트롤러에서 같은 응답(403 step_up_required)을 낼 때. */
	public static ApiException apiException() {
		return new ApiException(HttpStatus.FORBIDDEN, CODE, MESSAGE);
	}

}
