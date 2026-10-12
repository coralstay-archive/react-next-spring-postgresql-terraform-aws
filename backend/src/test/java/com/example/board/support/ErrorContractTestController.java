package com.example.board.support;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TASK-20 오류 응답 계약 테스트 전용 엔드포인트. 운영 코드에는 없다(src/test). 모두 인증이 필요한 경로(/test/**)다.
 */
@RestController
public class ErrorContractTestController {

	/** 응답에 새면 안 되는 내부 메시지. */
	public static final String SECRET = "SECRET-internal-detail-do-not-leak";

	private final JdbcTemplate jdbc;

	public ErrorContractTestController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 처리되지 않은 DataIntegrityViolation (TASK-20#2). 실제 PostgreSQL 오류를 낸다: {@code unique} 는 23505(유니크 위반),
	 * {@code data} 는 22P02(잘못된 숫자 표현), {@code plain} 은 SQLState 없는 위반.
	 */
	@GetMapping("/test/errors/db")
	public Map<String, Object> db(@RequestParam String kind) {
		switch (kind) {
			case "unique" -> jdbc.update("INSERT INTO categories (slug, name) VALUES ('notice', ?)", SECRET);
			case "data" -> jdbc.queryForObject("SELECT CAST(? AS INT)", Integer.class, SECRET);
			default -> throw new DataIntegrityViolationException(SECRET);
		}
		return Map.of("ok", true);
	}

	/** 처리되지 않은 예외 → 500 internal_error (TASK-20#1). */
	@GetMapping("/test/errors/boom")
	public Map<String, Object> boom() {
		throw new IllegalStateException(SECRET);
	}

	/** 컨트롤러 밖(서블릿 컨테이너)의 오류 처리 경로: sendError → /error. */
	@GetMapping("/test/errors/send-error")
	public void sendError(@RequestParam int status, HttpServletResponse response) throws IOException {
		response.sendError(status, SECRET);
	}

	/** 필수 파라미터 누락. */
	@GetMapping("/test/errors/required-param")
	public Map<String, Object> requiredParam(@RequestParam int count) {
		return Map.of("count", count);
	}

	/** 보안 체인 뒤 필터에서 터지는 예외. MVC 밖이므로 컨테이너가 /error 로 보낸다. */
	public static final class FailingFilter extends OncePerRequestFilter {

		public static final String PATH = "/test/errors/filter-boom";

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !request.getRequestURI().endsWith(PATH);
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			throw new IllegalStateException(SECRET);
		}

	}

}
