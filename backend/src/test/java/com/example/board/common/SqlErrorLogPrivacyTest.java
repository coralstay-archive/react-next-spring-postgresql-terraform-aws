package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.AuthProvider;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;

/**
 * TASK-21 계획 6 (TASK-20 리뷰 이월): JPA 경로의 SQL 오류는 Hibernate SqlExceptionHelper 가 자체 ERROR 로 남기고, PostgreSQL 오류의
 * Detail({@code Key (provider, subject)=(LOCAL, a@b.c)})에는 개인정보가 들어 있다. 드라이버가 Detail 을 예외 메시지에 싣지 않게
 * 해서(logServerErrorDetail=false) 로그·예외 어디에도 키 값이 남지 않게 한다. SQLState·제약 이름은 남는다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SqlErrorLogPrivacyTest {

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserIdentityRepository identities;

	@Test
	@DisplayName("TASK-21 (계획 6, TASK-20 이월) 유니크 위반의 DB Detail(이메일)은 Hibernate 로그·예외 메시지에 남지 않고 SQLState·제약 이름은 남는다")
	void uniqueViolationDetailIsNotLogged(CapturedOutput output) {
		String email = AuthClient.uniqueEmail();
		identities.saveAndFlush(UserIdentity.link(fixtures.user(), AuthProvider.LOCAL, email, email));

		Throwable failure = catchThrowable(() -> identities
			.saveAndFlush(UserIdentity.link(fixtures.user(), AuthProvider.LOCAL, email, email)));

		assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(SqlErrors.sqlState(failure)).isEqualTo("23505");
		assertThat(SqlErrors.constraintName(failure)).isNotBlank();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			assertThat(String.valueOf(cause.getMessage())).as(cause.getClass().getName()).doesNotContain(email);
		}
		assertThat(output).contains("23505").doesNotContain(email);
	}

}
