package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;

/**
 * TASK-23#1 (BE-3) 기본 타임아웃 설정. 테스트 컨텍스트는 운영과 같은 기본값을 쓴다(DbFailureTest 만 짧게 줄인다).
 */
@IntegrationTest
class DbTimeoutDefaultsTest {

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Test
	@DisplayName("TASK-23#1 세션마다 lock_timeout·statement_timeout 이 걸린다(무한 대기 없음)")
	void sessionTimeouts() {
		assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("5s");
		assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("10s");
	}

	@Test
	@DisplayName("TASK-23#1 풀의 모든 커넥션(Flyway 가 마이그레이션에 쓴 것 포함)에 같은 타임아웃이 걸려 있다 — Flyway 는 풀 밖의 자기 커넥션을 쓴다")
	void everyPooledConnectionHasTimeouts() throws Exception {
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		List<Connection> borrowed = new ArrayList<>();
		try {
			// 풀 크기만큼 동시에 빌려야 풀 안의 물리 커넥션을 빠짐없이 본다
			for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
				borrowed.add(dataSource.getConnection());
			}
			for (Connection connection : borrowed) {
				assertThat(show(connection, "statement_timeout")).isEqualTo("10s");
				assertThat(show(connection, "lock_timeout")).isEqualTo("5s");
			}
		}
		finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
		}
	}

	@Test
	@DisplayName("TASK-23#1 Flyway 는 앱 풀과 다른 자기 커넥션으로 statement_timeout·소켓 타임아웃 없이 마이그레이션한다(lock_timeout 은 유지)")
	void flywayUsesOwnConnection() throws Exception {
		DataSource migration = flyway.getConfiguration().getDataSource();
		assertThat(migration.isWrapperFor(HikariDataSource.class)).as("앱 풀(Hikari)이 아니다").isFalse();
		try (Connection connection = migration.getConnection()) {
			assertThat(show(connection, "statement_timeout")).isEqualTo("0");
			assertThat(show(connection, "lock_timeout")).isEqualTo("5s");
			// pgjdbc socketTimeout 은 네트워크 타임아웃으로 보인다(0 = 없음)
			assertThat(connection.getNetworkTimeout()).isZero();
		}
	}

	static String show(Connection connection, String setting) throws Exception {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("show " + setting)) {
			result.next();
			return result.getString(1);
		}
	}

	@Test
	@DisplayName("TASK-23#1 TASK-23#B2 커넥션 대기 + 소켓 타임아웃 + 세션 저장의 커넥션 대기가 30초 안이고, 살아 있는 DB 의 느린 쿼리는 소켓보다 먼저 statement_timeout 으로 끝난다")
	void responseBudget() throws Exception {
		HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
		Properties driver = hikari.getDataSourceProperties();
		Duration connection = Duration.ofMillis(hikari.getConnectionTimeout());
		Duration socket = Duration.ofSeconds(Long.parseLong(driver.getProperty("socketTimeout")));
		Duration statement = Duration.ofSeconds(10);
		assertThat(connection).isLessThanOrEqualTo(Duration.ofSeconds(5));
		assertThat(hikari.getValidationTimeout()).isLessThan(hikari.getConnectionTimeout());
		assertThat(connection.plus(socket).plus(connection)).isLessThan(Duration.ofSeconds(30));
		assertThat(statement).isLessThan(socket);
		assertThat(Long.parseLong(driver.getProperty("connectTimeout"))).isPositive();
	}

}
