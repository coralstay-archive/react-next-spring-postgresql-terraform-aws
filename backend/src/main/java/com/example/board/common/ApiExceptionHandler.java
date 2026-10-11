package com.example.board.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 컨트롤러 오류를 JSON 으로 바꾼다. 모양은 보안 체인의 401/403 과 같다({@code status, error, message}).
 * 입력 검증 실패는 {@code fields}(필드명 → 메시지)를 더한다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> handle(ApiException ex) {
		return body(ex.getStatus(), ex.getError(), ex.getMessage(), null);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> handle(MethodArgumentNotValidException ex) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return body(HttpStatus.BAD_REQUEST, "validation", "입력값이 올바르지 않습니다", fields);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<Map<String, Object>> handle(HttpMessageNotReadableException ex) {
		return body(HttpStatus.BAD_REQUEST, "bad_request", "요청 본문을 읽을 수 없습니다", null);
	}

	private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message,
			Map<String, String> fields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("error", error);
		body.put("message", message);
		if (fields != null) {
			body.put("fields", fields);
		}
		return ResponseEntity.status(status).body(body);
	}

}
