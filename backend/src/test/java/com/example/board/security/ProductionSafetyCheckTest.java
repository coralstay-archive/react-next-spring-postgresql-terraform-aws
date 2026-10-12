package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import com.example.board.BoardApplication;

/** TASK-21 운영 모드 판정과 기동 시 안전 설정 검사 (doc-2 BE-8). */
class ProductionSafetyCheckTest {

	static final String DEV_JWT = "dev-only-insecure-jwt-secret-change-me-0123456789";

	static final String DEV_OTP = "dev-only-insecure-otp-hash-key-change-me-0123456789";

	/** 운영에 맞는 설정. 각 테스트가 하나씩 망가뜨린다. */
	static MockEnvironment safeProduction() {
		return new MockEnvironment().withProperty("app.public-base-url", "https://board.example.com")
			.withProperty("app.jwt.secret", "Zt0q9w1Lr3m8Xc5Vb7Nn2Kk4Jj6Hh8Gg0Ff2Dd4Ss6Aa")
			.withProperty("app.otp.hash-key", "Qw8Er7Ty6Ui5Op4As3Df2Gh1Jk0Lz9Xc8Vb7Nm6Qa5W")
			.withProperty("app.security.cookie-secure", "true")
			.withProperty("spring.datasource.password", "R4nd0m-db-p4ssw0rd-from-ssm")
			.withProperty("app.client-ip.header", "CloudFront-Viewer-Address");
	}

	@Test
	@DisplayName("TASK-21#1 운영 모드 판정: PUBLIC_BASE_URL 이 https 면 운영, http 면 아니다. production-mode=true|false 로 강제할 수 있다")
	void productionModeDetection() {
		assertThat(ProductionMode.isProduction(new MockEnvironment())).as("기본(로컬 http)").isFalse();
		assertThat(ProductionMode.isProduction(
				new MockEnvironment().withProperty("app.public-base-url", "http://localhost:3000")))
			.isFalse();
		assertThat(ProductionMode.isProduction(
				new MockEnvironment().withProperty("app.public-base-url", " HTTPS://board.example.com")))
			.isTrue();
		assertThat(ProductionMode.isProduction(new MockEnvironment().withProperty("app.public-base-url", "https://x")
			.withProperty("app.security.production-mode", "false"))).isFalse();
		assertThat(ProductionMode.isProduction(new MockEnvironment().withProperty("app.public-base-url", "http://x")
			.withProperty("app.security.production-mode", "true"))).isTrue();
		assertThat(ProductionMode.isProduction(new MockEnvironment().withProperty("app.public-base-url", "https://x")
			.withProperty("app.security.production-mode", "auto"))).isTrue();
		assertThatThrownBy(() -> ProductionMode.isProduction(
				new MockEnvironment().withProperty("app.security.production-mode", "maybe")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("app.security.production-mode");
	}

	@Test
	@DisplayName("TASK-21#1 운영에 맞는 설정이면 문제가 없다")
	void safeProductionHasNoProblems() {
		assertThat(ProductionSafetyCheck.problems(safeProduction())).isEmpty();
		assertThatCode(() -> ProductionSafetyCheck.verify(safeProduction())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("TASK-21#1 TASK-21#B1 운영 모드에서 공개된 dev JWT_SECRET(개발 기본값·예시·E2E 값)이면 문제다")
	void publicJwtSecret() {
		for (String value : new String[] { DEV_JWT, "change-me-to-a-random-string-of-at-least-32-bytes",
				"e2e-only-jwt-signing-key-0123456789abcdef" }) {
			assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("app.jwt.secret", value)))
				.as(value)
				.singleElement()
				.asString()
				.contains("JWT_SECRET");
		}
	}

	@Test
	@DisplayName("TASK-21#1 TASK-21#B1 운영 모드에서 공개된 dev OTP_HASH_KEY 면 문제다")
	void publicOtpHashKey() {
		for (String value : new String[] { DEV_OTP, "change-me-to-another-random-string-of-at-least-32-bytes",
				"e2e-only-otp-hash-key-0123456789abcdef0123" }) {
			assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("app.otp.hash-key", value)))
				.as(value)
				.singleElement()
				.asString()
				.contains("OTP_HASH_KEY");
		}
	}

	@Test
	@DisplayName("TASK-21#1 운영 모드에서 COOKIE_SECURE=false(또는 미설정)면 문제다")
	void insecureCookie() {
		assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("app.security.cookie-secure", "false")))
			.singleElement()
			.asString()
			.contains("COOKIE_SECURE");
		assertThat(ProductionSafetyCheck.problems(new MockEnvironment()
			.withProperty("app.public-base-url", "https://board.example.com")
			.withProperty("app.jwt.secret", "Zt0q9w1Lr3m8Xc5Vb7Nn2Kk4Jj6Hh8Gg0Ff2Dd4Ss6Aa")
			.withProperty("app.otp.hash-key", "Qw8Er7Ty6Ui5Op4As3Df2Gh1Jk0Lz9Xc8Vb7Nm6Qa5W")
			.withProperty("spring.datasource.password", "R4nd0m-db-p4ssw0rd-from-ssm")
			.withProperty("app.client-ip.header", "CloudFront-Viewer-Address")))
			.singleElement()
			.asString()
			.contains("COOKIE_SECURE");
	}

	@Test
	@DisplayName("TASK-21#1 운영 모드에서 DB 비밀번호가 기본값(board)이거나 비어 있으면 문제다")
	void defaultDbPassword() {
		assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("spring.datasource.password", "board")))
			.singleElement()
			.asString()
			.contains("DB_PASSWORD");
		assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("spring.datasource.password", " ")))
			.singleElement()
			.asString()
			.contains("DB_PASSWORD");
	}

	@Test
	@DisplayName("TASK-21#2 운영 모드에서 CLIENT_IP_HEADER 가 비어 있으면 문제다(CloudFront 엣지 IP 를 모두가 공유해 IP 별 OTP 한도가 서비스 거부가 된다)")
	void clientIpHeaderRequired() {
		assertThat(ProductionSafetyCheck.problems(safeProduction().withProperty("app.client-ip.header", " ")))
			.singleElement()
			.asString()
			.contains("CLIENT_IP_HEADER");
	}

	@Test
	@DisplayName("TASK-21#1 TASK-21#B1 운영 모드 검사는 고칠 것을 모두 나열한 메시지로 기동을 중단한다")
	void verifyListsAllProblems() {
		MockEnvironment env = safeProduction().withProperty("app.jwt.secret", DEV_JWT)
			.withProperty("app.otp.hash-key", DEV_OTP)
			.withProperty("app.security.cookie-secure", "false")
			.withProperty("spring.datasource.password", "board");

		assertThatThrownBy(() -> ProductionSafetyCheck.verify(env)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("운영 모드")
			.hasMessageContaining("JWT_SECRET")
			.hasMessageContaining("OTP_HASH_KEY")
			.hasMessageContaining("COOKIE_SECURE")
			.hasMessageContaining("DB_PASSWORD")
			// 비밀값 자체는 메시지(로그)에 싣지 않는다
			.hasMessageNotContaining(DEV_JWT)
			.hasMessageNotContaining(DEV_OTP);
	}

	@Test
	@DisplayName("TASK-21#1 로컬·테스트(http)는 dev 기본값이어도 기동한다")
	void nonProductionAllowsDevDefaults() {
		MockEnvironment env = new MockEnvironment().withProperty("app.public-base-url", "http://localhost:3000")
			.withProperty("app.jwt.secret", DEV_JWT)
			.withProperty("app.otp.hash-key", DEV_OTP)
			.withProperty("app.security.cookie-secure", "false")
			.withProperty("spring.datasource.password", "board");

		assertThatCode(() -> ProductionSafetyCheck.verify(env)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("TASK-21#1 TASK-21#B1 운영 모드 + dev 비밀값이면 애플리케이션 컨텍스트가 뜨지 않는다(빈 생성 전에 실패)")
	void contextFailsInProductionWithDevSecrets() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ProductionSafetyCheck.class);

		runner.withPropertyValues("app.public-base-url=https://board.example.com", "app.jwt.secret=" + DEV_JWT,
				"app.otp.hash-key=" + DEV_OTP, "app.security.cookie-secure=true",
				"spring.datasource.password=R4nd0m-db-p4ssw0rd-from-ssm")
			.run(ctx -> assertThat(ctx).hasFailed()
				.getFailure()
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_SECRET")
				.hasMessageContaining("OTP_HASH_KEY"));
		runner.withPropertyValues("app.public-base-url=http://localhost:3000", "app.jwt.secret=" + DEV_JWT)
			.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	@DisplayName("TASK-21#1 TASK-21#B1 실제 애플리케이션도 운영 모드 + 기본 설정(dev 비밀값)이면 DB 에 붙기 전에 기동을 멈춘다")
	void realApplicationRefusesToStart() {
		// 명령행 인자는 env(개발자 .env 를 make 가 export)보다 우선한다. 나머지는 application.properties 기본값(dev 값)이다.
		SpringApplicationBuilder app = new SpringApplicationBuilder(BoardApplication.class).web(WebApplicationType.NONE)
			.logStartupInfo(false);

		assertThatThrownBy(() -> app.run("--app.public-base-url=https://board.example.com",
				"--app.jwt.secret=" + DEV_JWT, "--app.otp.hash-key=" + DEV_OTP, "--app.security.cookie-secure=false",
				"--spring.datasource.password=board", "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/none"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET")
			.hasMessageContaining("OTP_HASH_KEY")
			.hasMessageContaining("COOKIE_SECURE")
			.hasMessageContaining("DB_PASSWORD");
	}

}
