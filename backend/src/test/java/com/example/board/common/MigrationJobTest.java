package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.board.support.IntegrationTest;

/**
 * TASK-36#3 Flyway 마이그레이션을 앱 기동과 분리한 Job 으로 실행한다. k8s Job(board-migrate)은 백엔드 이미지의 {@link MigrationJob} 을
 * Spring 없이 {@code migrate} 로 돌리고, 백엔드 pod 의 초기화 컨테이너는 {@code await} 로 끝날 때까지 기다린다. 앱은 k8s 프로필에서 기동 시
 * 마이그레이션을 하지 않는다(docker-compose·테스트·AWS 의 기본 프로필은 그대로 기동 시 마이그레이션).
 */
@IntegrationTest
class MigrationJobTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	private Map<String, String> freshDatabase() {
		String database = "mj_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.execute("CREATE DATABASE " + database);
		String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		return Map.of("DB_URL", url, "DB_USERNAME", postgres.getUsername(), "DB_PASSWORD", postgres.getPassword(),
				"MIGRATION_AWAIT_TIMEOUT_SECONDS", "1", "MIGRATION_AWAIT_INTERVAL_MILLIS", "100");
	}

	private static JdbcTemplate db(Map<String, String> env) {
		return new JdbcTemplate(new DriverManagerDataSource(env.get("DB_URL"), env.get("DB_USERNAME"), env.get("DB_PASSWORD")));
	}

	@Test
	@DisplayName("TASK-36#3 migrate: 빈 DB 에 classpath:db/migration 을 모두 적용하고 0 으로 끝난다(다시 돌려도 0)")
	void migrateAppliesAllMigrations() {
		Map<String, String> env = freshDatabase();

		assertThat(MigrationJob.run(new String[] { "migrate" }, env)).isZero();

		Integer applied = db(env).queryForObject(
				"select count(*) from flyway_schema_history where success = true and version is not null", Integer.class);
		Integer expected = jdbc.queryForObject(
				"select count(*) from flyway_schema_history where success = true and version is not null", Integer.class);
		assertThat(applied).as("앱 기동 마이그레이션(테스트 DB)과 같은 수").isEqualTo(expected).isPositive();
		assertThat(MigrationJob.run(new String[] { "migrate" }, env)).as("이미 최신이면 아무것도 안 하고 0").isZero();
	}

	@Test
	@DisplayName("TASK-36#3 await: 적용 안 된 마이그레이션이 있으면 시간 초과 뒤 1, 모두 적용되면 0(백엔드 초기화 컨테이너)")
	void awaitWaitsForMigrations() {
		Map<String, String> env = freshDatabase();

		assertThat(MigrationJob.run(new String[] { "await" }, env)).as("빈 DB — 기다리다 시간 초과").isEqualTo(1);
		assertThat(MigrationJob.run(new String[] { "migrate" }, env)).isZero();
		assertThat(MigrationJob.run(new String[] { "await" }, env)).isZero();
	}

	@Test
	@DisplayName("TASK-36#3 모르는 명령·접속 정보 없음은 2(사용 오류)")
	void usageErrors() {
		assertThat(MigrationJob.run(new String[] { "nope" }, Map.of("DB_URL", "jdbc:postgresql://x/y"))).isEqualTo(2);
		assertThat(MigrationJob.run(new String[] { "migrate" }, Map.of())).isEqualTo(2);
	}

	@Test
	@DisplayName("TASK-36#3 k8s 프로필은 기동 시 마이그레이션을 끄고, 기본 프로필(docker-compose·테스트·AWS)은 켠 채로 둔다")
	void k8sProfileDisablesStartupMigration() throws IOException {
		assertThat(load("application-k8s.properties").getProperty("spring.flyway.enabled")).isEqualTo("false");
		assertThat(load("application.properties").getProperty("spring.flyway.enabled")).isNull();
		Integer applied = jdbc.queryForObject("select count(*) from flyway_schema_history where success = true", Integer.class);
		assertThat(applied).as("기본 프로필(이 테스트 컨텍스트)은 기동 시 마이그레이션").isPositive();
	}

	private static Properties load(String name) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = MigrationJobTest.class.getClassLoader().getResourceAsStream(name)) {
			assertThat(in).as(name).isNotNull();
			properties.load(in);
		}
		return properties;
	}

}
