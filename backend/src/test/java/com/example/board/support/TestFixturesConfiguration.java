package com.example.board.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** 통합 테스트 전용 픽스처(테스트 컨트롤러, 인증 헬퍼, OAuth 제공자 stub)를 모아 등록한다. */
@TestConfiguration(proxyBeanMethods = false)
@Import({ AuthFixtures.class, ProtectedResourceTestController.class })
public class TestFixturesConfiguration {

	/** TASK-7 만료·레이트리밋 테스트용 시계(앱의 Clock 빈을 대신한다). 움직인 테스트는 끝날 때 reset 한다. */
	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}

	/** TASK-4#5 OAuth 제공자 stub (WireMock). 컨텍스트가 하나라 서버도 하나다. */
	@Bean
	OAuthProviderStub oauthProviderStub() {
		return new OAuthProviderStub();
	}

	/** stub 주소를 앱의 OAuth 등록 설정으로 넣는다. 다른 빈보다 먼저 초기화되므로 등록 빈이 이 값을 본다. */
	@Bean
	DynamicPropertyRegistrar oauthProviderStubProperties(OAuthProviderStub stub) {
		return stub::registerProperties;
	}

}
