package com.example.board.common;

import java.sql.SQLException;

/** DB 예외에서 SQLState 를 꺼낸다 (TASK-20#2). 드라이버 클래스에 의존하지 않도록 원인 사슬의 {@link SQLException} 만 본다. */
public final class SqlErrors {

	private SqlErrors() {
	}

	/** 원인 사슬에서 처음 나오는 SQLState. 없으면 null. */
	public static String sqlState(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return null;
	}

}
