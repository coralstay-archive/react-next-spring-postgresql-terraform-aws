package com.example.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** TASK-1#2 백엔드가 PostgreSQL에 접속하고 /api/actuator/health 가 UP 이다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("TASK-1#2 /api/actuator/health 가 UP 이고 DB 상태도 UP 이다")
	void healthIsUpUnderApiContextPath() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/actuator/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

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
