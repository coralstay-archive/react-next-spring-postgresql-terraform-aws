package com.example.board.common;

import java.sql.SQLException;

import com.zaxxer.hikari.SQLExceptionOverride;

/**
 * Hikari 의 커넥션 폐기 판단 보강 (TASK-23#1, B2). {@code transaction_timeout} 을 넘기면 PostgreSQL 은 쿼리만 취소하지 않고 세션을 끝낸다
 * (FATAL, SQLState 25P04). Hikari 는 08xxx 같은 연결 오류만 폐기하므로 이 커넥션이 풀로 돌아가면 다음 요청이 끊긴 커넥션을 받는다 — 그래서
 * 25P04 는 반드시 폐기한다.
 *
 * <p>같은 트랜잭션의 뒤이은 롤백이 끊긴 커넥션에서 실패하면(08003) 원래 오류가 가려져 연결 실패처럼 보인다. 그래서 이 스레드(요청)에서
 * 세션 타임아웃이 있었다고 표시해 두고, {@link DbErrors} 가 그 경우를 503 {@code db_timeout} 으로 분류한다.
 */
public class DbSessionTimeoutOverride implements SQLExceptionOverride {

	/** transaction_timeout 으로 서버가 세션을 끝냈다. */
	static final String TRANSACTION_TIMEOUT = "25P04";

	@java.lang.Override
	public SQLExceptionOverride.Override adjudicate(SQLException ex) {
		if (TRANSACTION_TIMEOUT.equals(ex.getSQLState())) {
			DbErrors.markSessionTimedOut();
			return SQLExceptionOverride.Override.MUST_EVICT;
		}
		return SQLExceptionOverride.Override.CONTINUE_EVICT;
	}

}
