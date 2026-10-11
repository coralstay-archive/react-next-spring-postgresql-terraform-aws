package com.example.board.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** 통합 테스트 전용 픽스처(테스트 컨트롤러, 인증 헬퍼)를 모아 등록한다. */
@TestConfiguration(proxyBeanMethods = false)
@Import({ AuthFixtures.class, ProtectedResourceTestController.class })
public class TestFixturesConfiguration {
}
