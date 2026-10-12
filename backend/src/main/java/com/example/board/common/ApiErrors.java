package com.example.board.common;

import java.util.LinkedHashMap;
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

	/** 응답에 되돌려 주는 파라미터·필드 이름의 최대 길이 (TASK-20 L7). */
	static final int MAX_NAME_LENGTH = 100;

	/**
	 * 문서(docs/api.md 오류 응답)에 적은 상태 → 코드 표. HttpStatus 열거 이름에서 만들지 않는다(Spring 버전마다 이름이 바뀐다,
	 * 예: 413 PAYLOAD_TOO_LARGE → CONTENT_TOO_LARGE).
	 */
	private static final Map<Integer, String> CODES = Map.ofEntries(Map.entry(400, "bad_request"),
			Map.entry(401, "unauthorized"), Map.entry(403, "forbidden"), Map.entry(404, "not_found"),
			Map.entry(405, "method_not_allowed"), Map.entry(406, "not_acceptable"), Map.entry(409, "conflict"),
			Map.entry(413, "payload_too_large"), Map.entry(415, "unsupported_media_type"),
			Map.entry(429, RateLimitedException.CODE), Map.entry(500, INTERNAL_ERROR),
			Map.entry(503, "service_unavailable"));

	/** 상태 코드에 맞는 기본 오류 코드. 표에 없으면 {@code http_<상태>}(예: {@code http_418}). */
	public static String code(HttpStatusCode status) {
		return CODES.getOrDefault(status.value(), "http_" + status.value());
	}

	/** 사용자가 보낸 이름(파라미터·본문 필드)을 응답에 넣을 때 길이를 제한한다. */
	public static String name(String name) {
		return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH);
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

	/** 쿼리·경로 파라미터 하나의 오류: 400 {@code validation} + {@code fields{name: message}} (TASK-20#4). */
	public static ApiException invalidParam(String name, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, VALIDATION, message, Map.of(name(name), message));
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
