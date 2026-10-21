package com.example.board.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 필터 단계(Spring Session 의 세션 조회·저장, 정지 계정 확인 등)에서 난 DB 장애·락·타임아웃을 {@link DbErrors} 의 JSON 으로 바로 낸다
 * (TASK-23#1, B2). 컨테이너의 {@code /error} 로 넘기면 오류 디스패치가 같은 필터(세션 조회)를 다시 거치며 또 실패해서 HTML 500 이
 * 나간다. 그래서 모든 필터보다 바깥에서 받는다. MVC 안의 DB 오류는 {@link ApiExceptionHandler} 가 이미 같은 코드로 바꾼다.
 */
public class DbFailureFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(DbFailureFilter.class);

	private final JsonMapper json;

	public DbFailureFilter(JsonMapper json) {
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		catch (ServletException | IOException | RuntimeException ex) {
			ApiException db = DbErrors.classify(ex);
			if (db == null || response.isCommitted()) {
				throw ex;
			}
			log.warn("DB 오류 {} (SQLState {}, 필터 단계): {} {}", db.getError(), SqlErrors.sqlState(ex),
					request.getMethod(), request.getRequestURI());
			response.resetBuffer();
			response.setStatus(db.getStatus().value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getOutputStream()
				.write(json.writeValueAsBytes(ApiErrors.body(db.getStatus(), db.getError(), db.getMessage(), null)));
		}
	}

}
