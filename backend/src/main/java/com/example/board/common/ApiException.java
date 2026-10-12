package com.example.board.common;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * 컨트롤러·서비스가 던지는 API 오류. {@link ApiExceptionHandler} 가 보안 체인과 같은 JSON 모양
 * ({@code {"status","error","message"}})으로 바꾼다.
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	private final String error;

	/** 입력 검증 오류의 필드(또는 파라미터) 이름 → 메시지. 없으면 null. */
	private final Map<String, String> fields;

	public ApiException(HttpStatus status, String error, String message) {
		this(status, error, message, null);
	}

	public ApiException(HttpStatus status, String error, String message, Map<String, String> fields) {
		super(message);
		this.status = status;
		this.error = error;
		this.fields = fields == null ? null : Map.copyOf(fields);
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getError() {
		return error;
	}

	public Map<String, String> getFields() {
		return fields;
	}

}
