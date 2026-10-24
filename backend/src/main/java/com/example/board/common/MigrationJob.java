package com.example.board.common;

import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

/**
 * Flyway 마이그레이션을 앱 기동과 분리해 실행한다 (TASK-36#3). Spring 컨텍스트 없이 도는 main 이라 메모리·시간이 작다. 로컬 k8s(k3d)에서
 * 백엔드 이미지로 돈다: {@code java -cp /app/BOOT-INF/classes:/app/BOOT-INF/lib/* com.example.board.common.MigrationJob <명령>}.
 * <ul>
 * <li>{@code migrate} — Job(board-migrate). 적용 안 된 마이그레이션을 적용한다. PostgreSQL 이 아직 안 떴으면 접속을 다시 시도한다.</li>
 * <li>{@code await} — 백엔드 pod 의 초기화 컨테이너. 적용 안 된 마이그레이션이 없을 때까지 기다린다(앱이 옛 스키마로 뜨지 않게). 시간 초과면
 * 1.</li>
 * </ul>
 * 접속 정보는 앱과 같은 env(DB_URL·DB_USERNAME·DB_PASSWORD), 커넥션 설정은 앱 기동 마이그레이션과 같다
 * ({@link MigrationDataSourceConfiguration#migrationDataSource}: statement·transaction·socket 타임아웃 끔, lock_timeout 유지).
 * 종료 코드: 0 성공, 1 실패·시간 초과, 2 사용 오류.
 */
public final class MigrationJob {

	static final String LOCATION = "classpath:db/migration";

	private MigrationJob() {
	}

	public static void main(String[] args) {
		// Spring 밖이라 Logback 기본 설정(DEBUG)이 그대로다 — 드라이버 디버그 로그가 쏟아지지 않게 INFO 로
		if (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
			root.setLevel(Level.INFO);
		}
		System.exit(run(args, System.getenv()));
	}

	static int run(String[] args, Map<String, String> env) {
		String command = args.length > 0 ? args[0] : "";
		if (!command.equals("migrate") && !command.equals("await")) {
			System.err.println("사용: MigrationJob migrate|await");
			return 2;
		}
		String url = env.get("DB_URL");
		if (url == null || url.isBlank()) {
			System.err.println("DB_URL 이 없다");
			return 2;
		}
		try {
			return command.equals("migrate") ? migrate(env) : await(env);
		}
		catch (RuntimeException ex) {
			System.err.println("마이그레이션 실패: " + ex.getMessage());
			return 1;
		}
	}

	private static int migrate(Map<String, String> env) {
		MigrateResult result = flyway(env, intEnv(env, "MIGRATION_CONNECT_RETRIES", 60)).migrate();
		System.out.println("마이그레이션 완료: " + result.migrationsExecuted + "개 적용, 스키마 버전 " + result.targetSchemaVersion);
		return 0;
	}

	private static int await(Map<String, String> env) {
		// 경과 시간만 잰다(단조 시계) — 현재 시각은 쓰지 않는다
		long deadline = System.nanoTime() + 1_000_000_000L * intEnv(env, "MIGRATION_AWAIT_TIMEOUT_SECONDS", 600);
		long interval = intEnv(env, "MIGRATION_AWAIT_INTERVAL_MILLIS", 2000);
		Flyway flyway = flyway(env, 0);
		String last = "";
		while (true) {
			String state;
			try {
				MigrationInfo[] pending = flyway.info().pending();
				if (pending.length == 0) {
					System.out.println("마이그레이션 최신 — 앱을 띄운다");
					return 0;
				}
				state = "적용 안 된 마이그레이션 " + pending.length + "개(첫 버전 " + pending[0].getVersion() + ")";
			}
			catch (RuntimeException ex) {
				state = "DB 확인 실패: " + ex.getMessage();
			}
			if (!state.equals(last)) {
				System.out.println("마이그레이션 Job 을 기다린다: " + state);
				last = state;
			}
			if (System.nanoTime() - deadline >= 0) {
				System.err.println("시간 초과: " + state);
				return 1;
			}
			try {
				Thread.sleep(interval);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return 1;
			}
		}
	}

	private static Flyway flyway(Map<String, String> env, int connectRetries) {
		Properties driver = new Properties();
		driver.setProperty("connectTimeout", "5");
		driver.setProperty("logServerErrorDetail", env.getOrDefault("DB_LOG_SERVER_ERROR_DETAIL", "false"));
		DataSource dataSource = MigrationDataSourceConfiguration.migrationDataSource(env.get("DB_URL"),
				env.getOrDefault("DB_USERNAME", "board"), env.getOrDefault("DB_PASSWORD", "board"), driver,
				env.getOrDefault("DB_LOCK_TIMEOUT", "5s"));
		return Flyway.configure().dataSource(dataSource).locations(LOCATION).connectRetries(connectRetries).load();
	}

	private static int intEnv(Map<String, String> env, String name, int fallback) {
		String value = env.get(name);
		return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
	}

}
