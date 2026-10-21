package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;

import javax.sql.DataSource;

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

	@Test
	@DisplayName("TASK-23#1 세션마다 lock_timeout·statement_timeout 이 걸린다(무한 대기 없음)")
	void sessionTimeouts() {
		assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("5s");
		assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("10s");
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
