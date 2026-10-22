package com.example.board.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /error}: 컨트롤러 밖(필터, {@code sendError}, 컨테이너)에서 난 오류의 출구 (TASK-20#1). Boot 의 BasicErrorController
 * ({@code {timestamp,status,error:"Not Found",path}} 또는 HTML)를 대신해 {@link ApiErrors} 모양만 낸다. 내부 메시지
 * ({@code jakarta.servlet.error.message}, 예외)는 응답에 넣지 않고 5xx 일 때 서버 로그에만 남긴다(B1).
 */
@RestController
public class ApiErrorController implements ErrorController {

	private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

	@RequestMapping("${server.error.path:/error}")
	public ResponseEntity<Object> error(HttpServletRequest request) {
		HttpStatusCode status = status(request);
		// 예외가 있으면 서블릿 컨테이너가 이미 ERROR 로 남겼다. 예외 없는 5xx(sendError)만 여기서 남긴다(중복 로그 방지, TASK-20 L7).
		if (status.is5xxServerError() && request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) == null) {
			log.error("서버 오류 {}: {}", status.value(), request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
		}
		return ApiErrors.response(status, ApiErrors.code(status), ApiErrors.message(status), null);
	}

	/** 오류 전달이 아닌 직접 호출({@code GET /api/error})은 상태 정보가 없으므로 404 로 본다. */
	private static HttpStatusCode status(HttpServletRequest request) {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (code instanceof Integer value && value >= 400 && value <= 599) {
			return HttpStatusCode.valueOf(value);
		}
		return HttpStatus.NOT_FOUND;
	}

}
