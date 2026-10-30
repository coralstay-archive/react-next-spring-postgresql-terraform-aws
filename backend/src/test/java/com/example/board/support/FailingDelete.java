package com.example.board.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TASK-27 실패 주입 헬퍼: 표의 한 열이 특정 값인 행의 DELETE 만 DB 오류로 실패시키는 임시 트리거({@link FailingInsert} 의 DELETE 판). 값으로
 * 한정하므로 같은 표를 쓰는 다른 테스트에는 영향이 없다. {@link #close} 가 트리거와 함수를 지운다.
 *
 * <p>{@code table}·{@code column} 은 테스트 코드의 상수만 넘긴다(식별자라 바인딩할 수 없다). 값은 문자열 리터럴로 이스케이프한다.
 */
public final class FailingDelete implements AutoCloseable {

	private final JdbcTemplate jdbc;

	private final String table;

	private final String name;

	private FailingDelete(JdbcTemplate jdbc, String table, String name) {
		this.jdbc = jdbc;
		this.table = table;
		this.name = name;
	}

	public static FailingDelete on(JdbcTemplate jdbc, String table, String column, String value) {
		String name = "test_fail_delete_" + UUID.randomUUID().toString().replace("-", "");
		String literal = "'" + value.replace("'", "''") + "'";
		jdbc.execute("create function " + name + "() returns trigger language plpgsql as $$ begin if old." + column
				+ " = " + literal + " then raise exception 'injected failure'; end if; return old; end $$");
		jdbc.execute("create trigger " + name + " before delete on " + table + " for each row execute function " + name
				+ "()");
		return new FailingDelete(jdbc, table, name);
	}

	@Override
	public void close() {
		jdbc.execute("drop trigger if exists " + name + " on " + table);
		jdbc.execute("drop function if exists " + name + "()");
	}

}
