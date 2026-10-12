package com.example.board.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 오류 응답 본문 {@code {status, error, message, fields?}} 하나의 모양 (TASK-20 B1). {@link ApiExceptionHandler}(MVC 안)와
 * {@link ApiErrorController}(/error, MVC 밖)가 같이 쓴다. 보안 체인의 401/403 도 같은 모양이다.
 *
 * <p>Content-Type 을 {@code application/json} 으로 못박아 둔다. 그래야 {@code Accept: text/html} 같은 요청(406)에도 내용 협상을
 * 거치지 않고 같은 JSON 이 나간다.
 */
public final class ApiErrors {

	public static final String VALIDATION = "validation";

	public static final String INTERNAL_ERROR = "internal_error";

	private ApiErrors() {
	}

	/**
	 * 상태 코드에 맞는 기본 오류 코드: 500 은 {@code internal_error}, 나머지는 HTTP 상태 이름의 snake_case
	 * (예: {@code method_not_allowed}, {@code not_found}).
	 */
	public static String code(HttpStatusCode status) {
		if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			return INTERNAL_ERROR;
		}
		HttpStatus known = HttpStatus.resolve(status.value());
		if (known == null) {
			return status.is5xxServerError() ? INTERNAL_ERROR : "error";
		}
		return known.name().toLowerCase(Locale.ROOT);
	}

	/** 상태 코드에 맞는 기본 문장. 내부 예외 메시지는 쓰지 않는다(B1). */
	public static String message(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "잘못된 요청입니다";
			case 401 -> "인증이 필요합니다";
			case 403 -> "권한이 없습니다";
			case 404 -> "요청한 리소스를 찾을 수 없습니다";
			case 405 -> "지원하지 않는 메서드입니다";
			case 406 -> "지원하지 않는 응답 형식입니다";
			case 413 -> "요청 본문이 너무 큽니다";
			case 415 -> "지원하지 않는 Content-Type 입니다";
			case 503 -> "일시적으로 요청을 처리할 수 없습니다";
			default -> status.is5xxServerError() ? "서버 오류가 발생했습니다" : "요청을 처리하지 못했습니다";
		};
	}

	public static Map<String, Object> body(HttpStatusCode status, String error, String message,
			Map<String, String> fields) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status.value());
		body.put("error", error);
		body.put("message", message);
		if (fields != null && !fields.isEmpty()) {
			body.put("fields", fields);
		}
		return body;
	}

	public static ResponseEntity<Object> response(HttpStatusCode status, String error, String message,
			Map<String, String> fields) {
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_JSON)
			.body(body(status, error, message, fields));
	}

}
