package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;

/** TASK-1#2 백엔드가 PostgreSQL에 접속하고 /api/actuator/health 가 UP 이다. */
@IntegrationTest
class HealthEndpointTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	AuthFixtures fixtures;

	@Test
	@DisplayName("TASK-1#2 /api/actuator/health 가 UP 이고 DB 상태도 UP 이다 (세부 정보는 ADMIN 에게만)")
	void healthIsUpUnderApiContextPath() {
		HttpResponse<String> response = new Api(port).get("/actuator/health")
			.bearer(fixtures.bearer(fixtures.admin()))
			.send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
		assertThat(response.body()).contains("\"db\"");
	}

	@Test
	@DisplayName("TASK-1#2 Flyway 베이스라인 마이그레이션이 PostgreSQL에 적용된다")
	void flywayBaselineApplied() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success = true and version = '1'", Integer.class);
		assertThat(applied).isEqualTo(1);
	}

}
