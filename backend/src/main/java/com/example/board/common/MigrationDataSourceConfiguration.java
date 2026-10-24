package com.example.board.common;

import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Flyway 는 앱 커넥션 풀 밖의 자기 커넥션(풀 없음)으로 마이그레이션한다 (TASK-23#1). 앱 풀과 같은 접속 정보(DB_URL·DB_USERNAME·DB_PASSWORD
 * 와 드라이버 속성)를 쓰되 {@code statement_timeout}·{@code transaction_timeout}·pgjdbc {@code socketTimeout} 은 끈다(큰 인덱스 생성 등이
 * 잘리지 않게). {@code lock_timeout} 은 그대로 둔다(운영 중 테이블 잠금을 끝없이 기다리지 않게).
 *
 * <p>풀 커넥션에서 {@code SET statement_timeout = 0}(init-sqls)을 하면 마이그레이션 뒤 그 물리 커넥션이 타임아웃 없는 채로 풀에 돌아가
 * 앱 요청을 받는다 — Hikari 는 세션 변수를 되돌리지 않는다. 그래서 커넥션 자체를 나눈다.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationDataSourceConfiguration {

	@Bean
	FlywayConfigurationCustomizer migrationConnection(DataSource app,
			@Value("${app.db.lock-timeout}") String lockTimeout) {
		// 앱 풀(Hikari)의 접속 정보에서 만든다. Flyway 전용 접속 정보(spring.flyway.url·테스트의 service connection)가 있어도 이쪽이 우선한다
		return configuration -> configuration.dataSource(migrationDataSource(app, lockTimeout));
	}

	static DataSource migrationDataSource(DataSource app, String lockTimeout) {
		HikariDataSource pool;
		try {
			pool = app.unwrap(HikariDataSource.class);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("앱 DataSource 가 Hikari 가 아니다", ex);
		}
		return migrationDataSource(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword(),
				pool.getDataSourceProperties(), lockTimeout);
	}

	/**
	 * 마이그레이션 전용 커넥션(풀 없음): 드라이버 속성은 그대로, {@code statement_timeout}·{@code transaction_timeout}·{@code socketTimeout}
	 * 은 끄고 {@code lock_timeout} 만 둔다. 앱 기동 마이그레이션과 k8s 마이그레이션 Job({@link MigrationJob}, TASK-36#3)이 같이 쓴다.
	 */
	static DataSource migrationDataSource(String url, String username, String password, Properties driverProperties,
			String lockTimeout) {
		Properties properties = new Properties();
		properties.putAll(driverProperties);
		properties.setProperty("socketTimeout", "0");
		properties.setProperty("options",
				"-c lock_timeout=" + lockTimeout + " -c statement_timeout=0 -c transaction_timeout=0");
		DriverManagerDataSource migration = new DriverManagerDataSource(url, username, password);
		migration.setConnectionProperties(properties);
		return migration;
	}

}
