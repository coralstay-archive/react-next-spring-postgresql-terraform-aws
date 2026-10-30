package com.example.board.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.example.board.attachment.StorageProbe;
import com.example.board.attachment.UploadStorage;

/** 통합 테스트 전용 픽스처(테스트 컨트롤러, 인증 헬퍼, OAuth 제공자 stub)를 모아 등록한다. */
@TestConfiguration(proxyBeanMethods = false)
@Import({ AuthFixtures.class, BoardFixtures.class, ProtectedResourceTestController.class,
		ErrorContractTestController.class })
public class TestFixturesConfiguration {

	/** TASK-7 만료·레이트리밋 테스트용 시계(앱의 Clock 빈을 대신한다). 움직인 테스트는 끝날 때 reset 한다. */
	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}

	/**
	 * TASK-26 앱이 쓰는 첨부 저장소를 테스트가 들여다보고 고장 낼 수 있게 감싼다(평소에는 MinIO 저장소에 그대로 넘긴다). 별도 컨텍스트를 만들지
	 * 않으려고 여기 둔다.
	 */
	@Bean
	@Primary
	StorageProbe storageProbe(@Qualifier("uploadStorage") UploadStorage real) {
		return new StorageProbe(real);
	}

	/** TASK-4#5 OAuth 제공자 stub (WireMock). 컨텍스트가 하나라 서버도 하나다. */
	@Bean
	OAuthProviderStub oauthProviderStub() {
		return new OAuthProviderStub();
	}

	/** TASK-20#1 컨트롤러 밖(필터)에서 터지는 예외도 오류 계약을 따르는지 보는 필터. 그 경로만 건드린다. */
	@Bean
	FilterRegistrationBean<ErrorContractTestController.FailingFilter> failingFilter() {
		FilterRegistrationBean<ErrorContractTestController.FailingFilter> registration = new FilterRegistrationBean<>(
				new ErrorContractTestController.FailingFilter());
		registration.addUrlPatterns(ErrorContractTestController.FailingFilter.PATH);
		return registration;
	}

	/** stub 주소를 앱의 OAuth 등록 설정으로 넣는다. 다른 빈보다 먼저 초기화되므로 등록 빈이 이 값을 본다. */
	@Bean
	DynamicPropertyRegistrar oauthProviderStubProperties(OAuthProviderStub stub) {
		return stub::registerProperties;
	}

}
