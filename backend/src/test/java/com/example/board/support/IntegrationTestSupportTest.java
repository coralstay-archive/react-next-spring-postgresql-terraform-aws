package com.example.board.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** TASK-2#5 Testcontainers PostgreSQL 기반 통합 테스트 베이스. */
@IntegrationTest
class IntegrationTestSupportTest {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("TASK-2#5 @IntegrationTest 는 postgres:18 Testcontainer 에 붙고 Flyway 스키마가 적용돼 있다")
	void usesTestcontainersPostgres() {
		assertThat(postgres.isRunning()).isTrue();
		assertThat(postgres.getDockerImageName()).startsWith("postgres:18");
		assertThat(jdbcTemplate.queryForObject("show server_version", String.class)).startsWith("18");
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success and version = '2'", Integer.class)).isEqualTo(1);
	}

}
