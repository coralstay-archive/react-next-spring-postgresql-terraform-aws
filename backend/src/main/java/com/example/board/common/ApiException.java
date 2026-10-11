package com.example.board.common;

import org.springframework.http.HttpStatus;

/**
 * 컨트롤러·서비스가 던지는 API 오류. {@link ApiExceptionHandler} 가 보안 체인과 같은 JSON 모양
 * ({@code {"status","error","message"}})으로 바꾼다.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	private final String error;

	public ApiException(HttpStatus status, String error, String message) {
		super(message);
		this.status = status;
		this.error = error;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getError() {
		return error;
	}

}
