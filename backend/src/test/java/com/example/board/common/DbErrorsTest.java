package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;

/** TASK-23#1 DB 예외 분류 (SQLState·예외 타입 → 상태·코드). */
class DbErrorsTest {

	@ParameterizedTest(name = "TASK-23#1 SQLState {0} → {1} {2}")
	@CsvSource({ "55P03, 409, lock_timeout", "40P01, 409, lock_timeout", "57014, 503, db_timeout",
			"08006, 503, db_unavailable", "08001, 503, db_unavailable", "57P01, 503, db_unavailable",
			"57P03, 503, db_unavailable", "53300, 503, db_unavailable" })
	void bySqlState(String sqlState, int status, String code) {
		ApiException api = DbErrors.classify(new RuntimeException("wrapper", new SQLException("x", sqlState)));
		assertThat(api).isNotNull();
		assertThat(api.getStatus().value()).isEqualTo(status);
		assertThat(api.getError()).isEqualTo(code);
	}

	@Test
	@DisplayName("TASK-23#1 커넥션 풀 대기 초과·트랜잭션 시작 실패(SQLState 없음)는 503 db_unavailable")
	void connectionFailures() {
		assertThat(DbErrors.classify(new SQLTransientConnectionException("pool timeout")).getError())
			.isEqualTo("db_unavailable");
		assertThat(DbErrors.classify(new CannotCreateTransactionException("begin")).getError())
			.isEqualTo("db_unavailable");
	}

	@Test
	@DisplayName("TASK-23#1 DB 장애가 아닌 오류(제약 위반, 일반 예외)는 분류하지 않는다")
	void notDatabaseFailures() {
		assertThat(DbErrors.classify(new DataIntegrityViolationException("dup", new SQLException("x", "23505"))))
			.isNull();
		assertThat(DbErrors.classify(new IllegalStateException("boom"))).isNull();
	}

}
