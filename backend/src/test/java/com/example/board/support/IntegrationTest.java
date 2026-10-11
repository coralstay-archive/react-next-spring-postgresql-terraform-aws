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
		properties = "app.admin.email=" + IntegrationTest.ADMIN_EMAIL)
@Import({ TestcontainersConfiguration.class, TestFixturesConfiguration.class })
public @interface IntegrationTest {

	/** 테스트 컨텍스트의 첫 ADMIN 이메일 (TASK-3#6). 대소문자 정규화를 확인하려고 일부러 섞었다. */
	String ADMIN_EMAIL = "First.Admin@Board.Test";

}
