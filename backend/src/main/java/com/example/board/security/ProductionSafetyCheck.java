package com.example.board.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 운영 설정 fail-fast (TASK-21, doc-2 BE-8). 운영 모드({@link ProductionMode})에서 안전하지 않은 설정이면 기동을 멈춘다.
 *
 * <p>{@link BeanFactoryPostProcessor} 라서 일반 빈(DataSource·Flyway·웹 서버)을 만들기 전에 돈다 — DB 에 붙거나 요청을 받기
 * 전에 실패한다. 실패 메시지에는 고칠 env 키를 모두 나열하고, 비밀값 자체는 싣지 않는다. 로컬·테스트(http)에서는 검사하지 않는다
 * (개발용 기본값 경고는 각 컴포넌트가 남긴다).
 */
@Component
public class ProductionSafetyCheck implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

	private static final Log log = LogFactory.getLog(ProductionSafetyCheck.class);

	/**
	 * 저장소에 공개된 비밀값의 접두사: application.properties 개발 기본값({@code dev-only-}), example.env 예시({@code change-me}),
	 * scripts/e2e.sh 값({@code e2e-only-}). 이 값으로 서명한 JWT 는 누구나 위조할 수 있다(B1).
	 */
	static final List<String> PUBLIC_SECRET_PREFIXES = List.of("dev-only-", "change-me", "e2e-only-");

	/** docker-compose.yml·example.env·application.properties 의 로컬 DB 비밀번호. */
	static final String DEFAULT_DB_PASSWORD = "board";

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		verify(environment);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	/** 운영 모드면 {@link #problems} 를 확인하고, 하나라도 있으면 모두 나열한 {@link IllegalStateException} 을 던진다. */
	static void verify(Environment env) {
		if (!ProductionMode.isProduction(env)) {
			return;
		}
		List<String> problems = problems(env);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("운영 모드(" + ProductionMode.describe(env)
					+ ")에서 안전하지 않은 설정이라 기동하지 않는다. 다음을 고친다:\n  - " + String.join("\n  - ", problems));
		}
		log.info("운영 모드: 기동 시 안전 설정 검사 통과 (" + ProductionMode.describe(env) + ")");
	}

	/** 운영 모드라고 보고 고칠 것을 돌려준다. 비밀값은 싣지 않는다. */
	static List<String> problems(Environment env) {
		List<String> problems = new ArrayList<>();
		if (isPublicSecret(env.getProperty("app.jwt.secret"))) {
			problems.add("JWT_SECRET 이 저장소에 공개된 개발·예시 값이다(누구나 ADMIN JWT 를 위조할 수 있다). 32바이트 이상 무작위 값으로 바꾼다");
		}
		if (isPublicSecret(env.getProperty("app.otp.hash-key"))) {
			problems.add("OTP_HASH_KEY 가 저장소에 공개된 개발·예시 값이다. 32바이트 이상 무작위 값으로 바꾼다");
		}
		if (!Boolean.parseBoolean(env.getProperty("app.security.cookie-secure", "false").strip())) {
			problems.add("COOKIE_SECURE 가 true 가 아니다. HTTPS 운영에서는 세션·CSRF 쿠키에 Secure 를 붙인다(COOKIE_SECURE=true)");
		}
		String dbPassword = env.getProperty("spring.datasource.password", "");
		if (dbPassword.isBlank() || DEFAULT_DB_PASSWORD.equals(dbPassword)) {
			problems.add("DB_PASSWORD 가 비어 있거나 로컬 기본값(board)이다. 무작위 값으로 바꾼다");
		}
		if (env.getProperty("app.client-ip.header", "").isBlank()) {
			problems.add("CLIENT_IP_HEADER 가 비어 있다. CloudFront 뒤에서는 엣지 IP 를 모두가 공유해 IP 별 OTP 발송 한도가 서비스 거부가 된다"
					+ " (CLIENT_IP_HEADER=CloudFront-Viewer-Address)");
		}
		String smsProvider = env.getProperty("app.sms.provider", "").strip().toLowerCase(Locale.ROOT);
		if (!"aws".equals(smsProvider)) {
			problems.add("SMS_PROVIDER 가 aws 가 아니다(" + (smsProvider.isEmpty() ? "비어 있음 = console" : smsProvider)
					+ "). console 은 인증번호를 로그에만 남기고 memory 는 보내지 않는다 (SMS_PROVIDER=aws)");
		}
		return problems;
	}

	static boolean isPublicSecret(String value) {
		if (value == null || value.isBlank()) {
			return false; // 길이 검사(JwtProperties·OtpProperties)가 따로 기동을 막는다
		}
		String normalized = value.strip().toLowerCase(Locale.ROOT);
		return PUBLIC_SECRET_PREFIXES.stream().anyMatch(normalized::startsWith);
	}

}
