package com.example.board.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.example.board.TestcontainersConfiguration;

/**
 * TASK-2#5 통합 테스트 메타 애너테이션.
 *
 * <p>모든 통합 테스트가 같은 설정(실제 포트 + Testcontainers PostgreSQL + 테스트 전용 픽스처)을 쓰므로
 * 스프링 테스트 컨텍스트 캐시가 컨텍스트 하나를 공유하고, 그 안의 PostgreSQL 컨테이너도 하나만 뜬다.
 * 설정을 바꾸고 싶으면 이 애너테이션을 쓰지 말고 별도 컨텍스트를 의도적으로 만든다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.admin.email=" + IntegrationTest.ADMIN_EMAIL,
				"app.public-base-url=" + IntegrationTest.PUBLIC_BASE_URL,
				// TASK-7#1 SMS 는 메모리 수신함(InMemorySmsSender)으로 받는다
				"app.sms.provider=memory",
				// TASK-7#4 클라이언트 IP 는 CloudFront 처럼 신뢰 헤더로 받는다(테스트마다 다른 IP → IP 별 발송 한도를 나눠 쓰지 않는다)
				"app.client-ip.header=CloudFront-Viewer-Address",
				// TASK-25#3 OAuth 제공자 호출 읽기 타임아웃을 짧게(stub 은 ms 안에 응답한다) → 지연 stub 테스트가 빨리 끝난다
				"app.oauth2.http.read-timeout=" + IntegrationTest.OAUTH_READ_TIMEOUT,
				// TASK-26#5 스케줄 락 최소 유지 시간 없음 → 같은 정리 작업을 테스트가 연달아 부를 수 있다(운영 기본 10s)
				"app.scheduling.lock-at-least-for=PT0S" })
@Import({ TestcontainersConfiguration.class, TestFixturesConfiguration.class })
public @interface IntegrationTest {

	/** 테스트 컨텍스트의 첫 ADMIN 이메일 (TASK-3#6). 대소문자 정규화를 확인하려고 일부러 섞었다. */
	String ADMIN_EMAIL = "First.Admin@Board.Test";

	/**
	 * 테스트 컨텍스트의 공개 주소 (TASK-4#1). CloudFront 뒤처럼 앱이 받는 Host(localhost:port)와 다르게 둬서, OAuth redirect-uri 와
	 * 프론트 리다이렉트가 요청 Host 가 아니라 이 설정으로 만들어지는지 확인한다.
	 */
	String PUBLIC_BASE_URL = "http://board.test";

	/** TASK-25#3 통합 테스트의 OAuth 제공자 호출 읽기 타임아웃 (운영 기본 5s). */
	String OAUTH_READ_TIMEOUT = "3s";

}
