package com.example.board.common;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * DB 장애·락·타임아웃 예외를 API 오류로 바꾼다 (TASK-23#1, BE-3). {@link ApiExceptionHandler}(MVC 안)와 {@link ApiErrorController}
 * (필터·Spring Session 등 MVC 밖)가 같이 쓴다. 원인 사슬의 SQLState 를 먼저 보고, 없으면 연결 실패 예외 타입을 본다.
 * <ul>
 * <li>55P03(lock_not_available: lock_timeout)·40P01(교착): 409 {@code lock_timeout} — 경쟁이라 다시 시도하면 된다.</li>
 * <li>57014(query_canceled: statement_timeout): 503 {@code db_timeout}.</li>
 * <li>08xxx(연결)·57P01~03(서버 종료·기동 중)·53xxx(자원 부족), 커넥션 풀 대기 초과, 트랜잭션 시작 실패, 끊긴 커넥션의 롤백·커밋
 * 실패: 503 {@code db_unavailable}.</li>
 * </ul>
 */
public final class DbErrors {

	public static final String DB_UNAVAILABLE = "db_unavailable";

	public static final String DB_TIMEOUT = "db_timeout";

	public static final String LOCK_TIMEOUT = "lock_timeout";

	private DbErrors() {
	}

	/** DB 탓 오류면 그 응답, 아니면 null. */
	public static ApiException classify(Throwable ex) {
		String sqlState = SqlErrors.sqlState(ex);
		if (sqlState != null) {
			if ("55P03".equals(sqlState) || "40P01".equals(sqlState)) {
				return lockTimeout();
			}
			if ("57014".equals(sqlState)) {
				return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, DB_TIMEOUT,
						"요청 처리 시간이 너무 오래 걸려 중단했습니다. 잠시 후 다시 시도하세요");
			}
			if (sqlState.startsWith("08") || sqlState.startsWith("53") || sqlState.startsWith("57P")) {
				return unavailable();
			}
		}
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException
					|| cause instanceof SQLRecoverableException || cause instanceof CannotCreateTransactionException
					|| cause instanceof CannotGetJdbcConnectionException
					|| cause instanceof DataAccessResourceFailureException) {
				return unavailable();
			}
			// 트랜잭션 끝(롤백·커밋)에서 커넥션이 이미 끊겨 있으면 원래 예외(소켓 타임아웃 등)가 이 예외로 덮인다
			if ((cause instanceof org.hibernate.TransactionException || cause instanceof TransactionSystemException)
					&& cause.getCause() instanceof SQLException) {
				return unavailable();
			}
			if (cause instanceof SQLException) {
				// SQLState 가 있는 다른 SQL 오류(제약 위반 등)는 여기서 다루지 않는다
				break;
			}
		}
		return null;
	}

	static ApiException lockTimeout() {
		return new ApiException(HttpStatus.CONFLICT, LOCK_TIMEOUT, "다른 요청이 같은 데이터를 쓰고 있습니다. 잠시 후 다시 시도하세요");
	}

	static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, DB_UNAVAILABLE,
				"데이터베이스에 연결할 수 없습니다. 잠시 후 다시 시도하세요");
	}

}
