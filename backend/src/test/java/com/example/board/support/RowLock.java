package com.example.board.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TASK-23 동시성 테스트 헬퍼: 테스트 커넥션이 행을 잠가 두고, 앱 요청들이 그 행 잠금 앞에서 줄 서는 것을 {@code pg_stat_activity} 로
 * 확인한 뒤 풀어서 경쟁 순서를 결정적으로 만든다. 시간을 정해 기다리지 않고 조건(대기 중인 백엔드 수)만 폴링한다.
 *
 * <p>쓰는 테스트는 {@link #LOCK_TIMEOUT} 등으로 DB 타임아웃을 늘린 컨텍스트에서 돈다(기본 lock_timeout·statement_timeout 5s 면 느린 CI 에서
 * 줄 선 첫 요청이 풀기 전에 409 lock_timeout·503 db_timeout 으로 끝날 수 있다).
 */
public final class RowLock implements AutoCloseable {

	/** 줄 선 요청이 기다리는 동안 lock_timeout·statement_timeout 에 걸리지 않게. */
	public static final String LOCK_TIMEOUT = "30s";

	/** 잠금을 쥔 테스트 트랜잭션도 transaction_timeout 에 끊기지 않게. */
	public static final String TRANSACTION_TIMEOUT = "60s";

	/** 잠금 대기 중인 문장이 소켓 타임아웃에 끊기지 않게. */
	public static final String SOCKET_TIMEOUT_SECONDS = "60";

	private static final Duration DEADLINE = Duration.ofSeconds(20);

	private final Connection connection;

	private final JdbcTemplate jdbc;

	private RowLock(Connection connection, JdbcTemplate jdbc) {
		this.connection = connection;
		this.jdbc = jdbc;
	}

	/** {@code sql}(예: {@code select id from posts where id = ? for update})을 열린 트랜잭션에서 실행해 잠금을 쥔다. */
	public static RowLock hold(DataSource dataSource, String sql, Object... args) throws SQLException {
		Connection connection = dataSource.getConnection();
		connection.setAutoCommit(false);
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				statement.setObject(i + 1, args[i]);
			}
			statement.execute();
		}
		return new RowLock(connection, new JdbcTemplate(dataSource));
	}

	/** posts 를 건드리는 문장 중 잠금(Lock)을 기다리는 다른 백엔드가 {@code n} 개가 될 때까지 기다린다. */
	public void awaitWaiters(int n) throws InterruptedException {
		Instant deadline = Instant.now().plus(DEADLINE);
		while (waiters() < n) {
			if (Instant.now().isAfter(deadline)) {
				throw new IllegalStateException("잠금 대기 백엔드가 " + n + "개가 되지 않았다: " + waiters());
			}
			Thread.sleep(10);
		}
	}

	private int waiters() {
		Integer count = jdbc.queryForObject(
				"select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
						+ " and pid <> pg_backend_pid() and query ilike '%posts%'",
				Integer.class);
		return count == null ? 0 : count;
	}

	/** 커밋해서 잠금을 푼다(쥔 트랜잭션이 바꾼 것이 있으면 반영된다). */
	public void commit() throws SQLException {
		connection.commit();
	}

	@Override
	public void close() throws SQLException {
		try {
			if (!connection.getAutoCommit()) {
				connection.rollback();
			}
		}
		finally {
			connection.close();
		}
	}

}
