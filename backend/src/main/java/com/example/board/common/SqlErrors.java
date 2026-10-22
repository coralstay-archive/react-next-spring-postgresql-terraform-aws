package com.example.board.common;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;

/** DB 예외에서 SQLState 를 꺼낸다 (TASK-20#2). 드라이버 클래스에 의존하지 않도록 원인 사슬의 {@link SQLException} 만 본다. */
public final class SqlErrors {

	private SqlErrors() {
	}

	/**
	 * 이 예외가 이름이 {@code constraint} 인 제약 위반인지 (TASK-20#5). Hibernate 가 뽑은 제약 이름을 먼저 보고, 없으면 DB 메시지에
	 * 그 이름이 있는지 본다.
	 */
	public static boolean violates(Throwable ex, String constraint) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
				return constraint.equalsIgnoreCase(violation.getConstraintName());
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getMessage() != null) {
				return sql.getMessage().contains("\"" + constraint + "\"");
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return false;
	}

	private static final Pattern CONSTRAINT_IN_MESSAGE = Pattern.compile("constraint \"([^\"]{1,63})\"");

	/**
	 * 위반한 제약 이름 (로그용). Hibernate 가 뽑은 이름, 없으면 DB 메시지의 {@code constraint "..."} 부분만 꺼낸다(메시지 전체는
	 * 키 값 같은 개인정보를 담으므로 쓰지 않는다). 없으면 null.
	 */
	public static String constraintName(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
				return violation.getConstraintName();
			}
		}
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getMessage() != null) {
				Matcher matcher = CONSTRAINT_IN_MESSAGE.matcher(sql.getMessage());
				return matcher.find() ? matcher.group(1) : null;
			}
		}
		return null;
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
