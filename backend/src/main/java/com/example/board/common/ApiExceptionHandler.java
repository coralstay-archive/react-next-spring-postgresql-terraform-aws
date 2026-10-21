package com.example.board.common;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 컨트롤러 오류를 JSON 으로 바꾼다. 모양은 보안 체인의 401/403 과 같다({@code status, error, message}, {@link ApiErrors}).
 * 입력 검증 실패는 {@code fields}(필드명 → 메시지)를 더한다.
 *
 * <p>TASK-20#1 (BE-1): {@link ResponseEntityExceptionHandler} 를 상속해 Spring MVC 표준 예외(405·415·406·404·
 * ResponseStatusException 등)도 Boot 기본 모양 대신 같은 모양과 snake_case 코드로 낸다. 처리되지 않은 예외는 500
 * {@code internal_error} 이고 서버 로그에만 원인을 남긴다(B1). MVC 밖(필터·컨테이너) 오류는 {@link ApiErrorController} 가 맡는다.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handle(ApiException ex) {
		return ApiErrors.response(ex.getStatus(), ex.getError(), ex.getMessage(), ex.getFields());
	}

	/**
	 * 앞단 검증을 지나 DB 에서 난 무결성 위반 (TASK-20#2, B2). SQLState 로 나눈다.
	 * <ul>
	 * <li>22xxx(데이터 예외): 400 {@code validation}</li>
	 * <li>23505(유니크)·23503(FK) 등 동시성·참조 충돌: 409 {@code conflict}</li>
	 * <li>23502(NOT NULL)·23514(CHECK): 앞단 검증이 놓친 서버 버그 → 500 {@code internal_error} + ERROR 로그</li>
	 * </ul>
	 * 로그에는 SQLState 와 제약 이름만 남긴다. DB 메시지는 {@code Key (email)=(...)} 같은 개인정보를 담으므로 남기지 않는다.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Object> handle(DataIntegrityViolationException ex, WebRequest request) {
		String sqlState = SqlErrors.sqlState(ex);
		String constraint = SqlErrors.constraintName(ex);
		if ("23502".equals(sqlState) || "23514".equals(sqlState)) {
			log.error("무결성 위반(서버 버그) SQLState {} 제약 {}: {}", sqlState, constraint, describe(request));
			HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
			return ApiErrors.response(status, ApiErrors.INTERNAL_ERROR, ApiErrors.message(status), null);
		}
		log.warn("처리되지 않은 무결성 위반 SQLState {} 제약 {}: {}", sqlState, constraint, describe(request));
		if (sqlState != null && sqlState.startsWith("22")) {
			return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "입력값이 올바르지 않습니다", null);
		}
		return ApiErrors.response(HttpStatus.CONFLICT, "conflict", "다른 데이터와 충돌해 처리하지 못했습니다", null);
	}

	/**
	 * 낙관적 잠금 충돌 (TASK-23#3): 읽은 뒤 다른 요청이 같은 행을 먼저 바꿔 커밋했다. 409 {@code conflict}. 다시 읽고 시도하면 된다.
	 */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<Object> handle(OptimisticLockingFailureException ex, WebRequest request) {
		log.info("낙관적 잠금 충돌: {}", describe(request));
		return ApiErrors.response(HttpStatus.CONFLICT, "conflict", "다른 사람이 먼저 바꿨습니다. 새로 불러온 뒤 다시 시도하세요", null);
	}

	/** 429: 같은 본문 + {@code Retry-After}. */
	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<Object> handle(RateLimitedException ex) {
		ResponseEntity<Object> response = handle((ApiException) ex);
		return ResponseEntity.status(response.getStatusCode())
			.headers(response.getHeaders())
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
			.body(response.getBody());
	}

	/**
	 * 그 밖의 예외는 500 {@code internal_error}. 원인은 서버 로그(ERROR)에만 남긴다. 인증·인가 예외(예: {@code @PreAuthorize} 거부)는
	 * 다시 던져 보안 체인({@code ExceptionTranslationFilter})이 401/403 으로 바꾸게 한다.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
		// @ExceptionHandler(Exception.class) 는 원인 기반 매칭을 막으므로 원인 사슬을 직접 본다 (TASK-20 L1).
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof AccessDeniedException || cause instanceof AuthenticationException) {
				throw ex;
			}
			if (cause instanceof RateLimitedException limited) {
				return handle(limited);
			}
			if (cause instanceof ApiException api) {
				return handle(api);
			}
			if (cause instanceof RequestBodyLimitFilter.TooLargeException) {
				return tooLarge();
			}
		}
		ApiException db = DbErrors.classify(ex);
		if (db != null) {
			// DB 장애·락·타임아웃 (TASK-23#1). SQLState 만 남긴다(DB 메시지는 키 값 같은 개인정보를 담을 수 있다).
			log.warn("DB 오류 {} (SQLState {}, {}): {}", db.getError(), SqlErrors.sqlState(ex), ex.getClass().getSimpleName(),
					describe(request));
			return isCommitted(request) ? null : handle(db);
		}
		log.error("처리되지 않은 예외: {}", describe(request), ex);
		if (isCommitted(request)) {
			return null;
		}
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return ApiErrors.response(status, ApiErrors.INTERNAL_ERROR, ApiErrors.message(status), null);
	}

	/** 컨트롤러 메서드 파라미터 제약(@Min 등) 위반: 400 {@code validation} + {@code fields}(파라미터 이름, TASK-20 L6). */
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (ParameterValidationResult result : ex.getParameterValidationResults()) {
			String name = parameterName(result.getMethodParameter());
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				fields.putIfAbsent(ApiErrors.name(name), error.getDefaultMessage());
			}
		}
		return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "입력값이 올바르지 않습니다", fields);
	}

	private static String parameterName(MethodParameter parameter) {
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		if (requestParam != null && !requestParam.name().isEmpty()) {
			return requestParam.name();
		}
		PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
		if (pathVariable != null && !pathVariable.name().isEmpty()) {
			return pathVariable.name();
		}
		String name = parameter.getParameterName();
		return name != null ? name : "arg" + parameter.getParameterIndex();
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			fields.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "입력값이 올바르지 않습니다", fields);
	}

	/**
	 * 본문을 읽지 못함. 특정 필드 탓이면(알 수 없는 필드 — {@code fail-on-unknown-properties}, 타입 오류, 제어 문자) 400
	 * {@code validation} + {@code fields}(본문 경로 → 메시지, TASK-20#3). 문법 오류·빈 본문처럼 필드가 없으면 400 {@code bad_request}.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (tooLarge(ex)) {
			return tooLarge();
		}
		if (ex.getCause() instanceof MismatchedInputException mismatch) {
			String path = jsonPath(mismatch);
			if (path != null) {
				return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "입력값이 올바르지 않습니다",
						Map.of(ApiErrors.name(path), fieldMessage(mismatch)));
			}
		}
		return ApiErrors.response(HttpStatus.BAD_REQUEST, "bad_request", "요청 본문을 읽을 수 없습니다", null);
	}

	/** 길이를 모르는 본문이 상한을 넘었다 (TASK-23#2, {@link RequestBodyLimitFilter}). */
	private static boolean tooLarge(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof RequestBodyLimitFilter.TooLargeException) {
				return true;
			}
		}
		return false;
	}

	private static ResponseEntity<Object> tooLarge() {
		HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
		return ApiErrors.response(status, ApiErrors.code(status), ApiErrors.message(status), null);
	}

	private static String fieldMessage(MismatchedInputException ex) {
		if (ex instanceof InputIntegrityConfiguration.ForbiddenCharacterException) {
			return ControlCharacters.MESSAGE;
		}
		if (ex instanceof UnrecognizedPropertyException) {
			return "알 수 없는 필드입니다";
		}
		return "형식이 올바르지 않습니다";
	}

	/** 필수 쿼리 파라미터 누락: 다른 파라미터 오류와 같은 400 {@code validation} + {@code fields}. */
	@Override
	protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		String name = ApiErrors.name(ex.getParameterName());
		return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "필수 파라미터가 없습니다: " + name,
				Map.of(name, "필수 파라미터입니다"));
	}

	/**
	 * 쿼리·경로 파라미터 형식 오류(예: {@code ?page=abc}, 없는 enum 값). 다른 파라미터 오류와 같은 400 {@code validation} +
	 * {@code fields}(파라미터 이름, TASK-20#4).
	 */
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
				: ex.getPropertyName();
		name = name == null ? "parameter" : ApiErrors.name(name);
		return ApiErrors.response(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION, "파라미터 형식이 올바르지 않습니다: " + name,
				Map.of(name, "형식이 올바르지 않습니다"));
	}

	/**
	 * 나머지 Spring MVC 표준 예외(405·415·406·404·ResponseStatusException 등)의 공통 출구. 상태에 맞는 snake_case 코드와
	 * 고정 문장을 쓰고, 프레임워크가 넣은 헤더(예: 405 의 {@code Allow})는 유지한다. 5xx 는 서버 로그를 남긴다.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (isCommitted(request)) {
			return null;
		}
		if (statusCode.is5xxServerError()) {
			log.error("서버 오류 {}: {}", statusCode.value(), describe(request), ex);
		}
		ResponseEntity<Object> response = ApiErrors.response(statusCode, ApiErrors.code(statusCode),
				ApiErrors.message(statusCode), null);
		HttpHeaders merged = new HttpHeaders();
		merged.putAll(headers);
		merged.putAll(response.getHeaders());
		return new ResponseEntity<>(response.getBody(), merged, statusCode);
	}

	/** Jackson 예외의 본문 경로. Bean Validation 의 필드 이름과 같은 꼴({@code title}, {@code tags[2]}). 경로가 없으면 null. */
	private static String jsonPath(JacksonException ex) {
		StringBuilder path = new StringBuilder();
		for (JacksonException.Reference reference : ex.getPath()) {
			if (reference.getPropertyName() != null) {
				if (!path.isEmpty()) {
					path.append('.');
				}
				path.append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				path.append('[').append(reference.getIndex()).append(']');
			}
		}
		return path.isEmpty() ? null : path.toString();
	}

	private static boolean isCommitted(WebRequest request) {
		if (request instanceof ServletWebRequest servlet) {
			HttpServletResponse response = servlet.getResponse();
			return response != null && response.isCommitted();
		}
		return false;
	}

	private static String describe(WebRequest request) {
		if (request instanceof ServletWebRequest servlet) {
			return servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI();
		}
		return request.getDescription(false);
	}

}
