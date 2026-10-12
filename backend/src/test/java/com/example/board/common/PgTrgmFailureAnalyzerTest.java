package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StringUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.board.support.IntegrationTest;

/**
 * TASK-21#5 (doc-2 BE-12) V8 의 {@code CREATE EXTENSION pg_trgm} 이 권한 부족으로 실패하면 기동 실패 메시지가 원인과 고치는 법을
 * 알려 준다. 실제 PostgreSQL 에 DB CREATE 권한이 없는 사용자를 만들어 마이그레이션을 돌려 본다.
 */
@IntegrationTest
class PgTrgmFailureAnalyzerTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	private final PgTrgmFailureAnalyzer analyzer = new PgTrgmFailureAnalyzer();

	@Test
	@DisplayName("TASK-21#5 DB CREATE 권한이 없는 사용자로 마이그레이션하면 V8 에서 실패하고, 분석기가 pg_trgm 권한 요구사항과 조치를 알려 준다")
	void explainsMissingExtensionPrivilege() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String database = "trgm_" + suffix;
		String role = "no_create_" + suffix;
		String password = "pw-" + suffix;
		jdbc.execute("CREATE DATABASE " + database);
		jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + password + "'");
		String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		// 테이블은 만들 수 있지만(public 스키마 권한) DB 에 CREATE 권한은 없다 → trusted 확장(pg_trgm)도 만들 수 없다
		new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()))
			.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + role);

		Throwable failure = catchThrowable(() -> Flyway.configure()
			.dataSource(url, role, password)
			.locations("classpath:db/migration")
			.load()
			.migrate());

		assertThat(failure).as("V8 에서 실패해야 한다").isNotNull();
		FailureAnalysis analysis = analyzer.analyze(failure);
		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription()).contains("pg_trgm").contains("V8");
		assertThat(analysis.getAction()).contains("CREATE EXTENSION").contains("CREATE 권한").contains("first-deploy");
	}

	@Test
	@DisplayName("TASK-21#5 pg_trgm 과 상관없는 실패는 분석하지 않는다(다른 분석기에 맡긴다)")
	void ignoresUnrelatedFailures() {
		assertThat(analyzer.analyze(new IllegalStateException("something else"))).isNull();
		assertThat(analyzer.analyze(new RuntimeException("wrapper", new IllegalArgumentException("x")))).isNull();
	}

	@Test
	@DisplayName("TASK-21#5 분석기는 spring.factories 로 등록돼 기동 실패 보고서에 쓰인다")
	void registeredAsFailureAnalyzer() throws Exception {
		List<String> registered = new ArrayList<>();
		for (URL url : Collections.list(getClass().getClassLoader().getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION))) {
			String value = PropertiesLoaderUtils.loadProperties(new UrlResource(url))
				.getProperty(FailureAnalyzer.class.getName());
			if (value != null) {
				registered.addAll(StringUtils.commaDelimitedListToSet(value.replaceAll("\\s", "")));
			}
		}
		assertThat(registered).contains(PgTrgmFailureAnalyzer.class.getName());
	}

}
