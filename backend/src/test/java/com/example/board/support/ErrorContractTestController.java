package com.example.board.support;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.validation.constraints.Min;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.board.common.ApiException;

/**
 * TASK-20 오류 응답 계약 테스트 전용 엔드포인트. 운영 코드에는 없다(src/test). 모두 인증이 필요한 경로(/test/**)다.
 */
@RestController
public class ErrorContractTestController {

	/** 응답에 새면 안 되는 내부 메시지. */
	public static final String SECRET = "SECRET-internal-detail-do-not-leak";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	public ErrorContractTestController(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 처리되지 않은 DataIntegrityViolation (TASK-20#2). 실제 PostgreSQL 오류를 낸다: {@code unique}·{@code unique-pii} 는
	 * 23505(유니크 위반), {@code fk} 는 23503, {@code not-null} 은 23502, {@code check} 는 23514, {@code data} 는 22P02(잘못된
	 * 숫자 표현), {@code plain} 은 SQLState 없는 위반.
	 */
	@GetMapping("/test/errors/db")
	public Map<String, Object> db(@RequestParam String kind) {
		switch (kind) {
			case "unique" -> jdbc.update("INSERT INTO categories (slug, name) VALUES ('notice', ?)", SECRET);
			case "unique-pii" -> {
				// 유니크 위반 메시지에 키 값(Key (slug)=(...))이 담긴다 — 로그에 남으면 안 된다
				String slug = "pii-" + System.nanoTime();
				jdbc.update("INSERT INTO categories (slug, name) VALUES (?, 'x')", slug);
				jdbc.update("INSERT INTO categories (slug, name) VALUES (?, 'x')", slug);
			}
			case "fk" -> jdbc.update(
					"INSERT INTO posts (author_id, title, content) VALUES (-424242, 't', 'c')");
			case "not-null" -> jdbc.update("INSERT INTO categories (slug, name) VALUES ('nn-x', NULL)");
			case "check" -> jdbc.execute("DO $$ BEGIN RAISE EXCEPTION 'check' USING ERRCODE = '23514'; END $$");
			case "data" -> jdbc.queryForObject("SELECT CAST(? AS INT)", Integer.class, SECRET);
			default -> throw new DataIntegrityViolationException(SECRET);
		}
		return Map.of("ok", true);
	}

	/** statement_timeout 보다 오래 걸리는 쿼리 (TASK-23#1). 타임아웃을 짧게 준 컨텍스트(DbFailureTest)에서만 부른다. */
	@GetMapping("/test/errors/slow-query")
	public Map<String, Object> slowQuery() {
		jdbc.queryForObject("SELECT pg_sleep(5)::text", String.class);
		return Map.of("ok", true);
	}

	/**
	 * 문장 하나하나는 statement_timeout 안이지만 합치면 transaction_timeout 을 넘는 트랜잭션 (TASK-23#1, B2). 느리지만 살아 있는 DB 에서
	 * 문장이 여러 개인 요청. 타임아웃을 짧게 준 컨텍스트(DbFailureTest: statement 1s, transaction 2s)에서만 부른다.
	 */
	@GetMapping("/test/errors/slow-transaction")
	public Map<String, Object> slowTransaction() {
		transaction.executeWithoutResult(status -> {
			for (int i = 0; i < 8; i++) {
				jdbc.queryForObject("SELECT pg_sleep(0.6)::text", String.class);
			}
		});
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

	/** 다른 예외에 감싸인 ApiException 도 그 코드로 (TASK-20#1 L1). */
	@GetMapping("/test/errors/wrapped")
	public Map<String, Object> wrapped() {
		throw new IllegalStateException("wrapper",
				new ApiException(HttpStatus.CONFLICT, "wrapped_code", "감싸인 오류입니다"));
	}

	/** 메서드 파라미터 검증(@Min) 실패 → HandlerMethodValidationException (TASK-20#1 L6). */
	@GetMapping("/test/errors/method-validation")
	public Map<String, Object> methodValidation(@RequestParam @Min(1) int n) {
		return Map.of("n", n);
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
