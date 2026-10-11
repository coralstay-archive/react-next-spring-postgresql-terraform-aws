package com.example.board.support;

import org.springframework.boot.test.context.TestConfiguration;

/** 통합 테스트 전용 픽스처(테스트 컨트롤러, 세션 헬퍼 등)를 모아 등록한다. */
@TestConfiguration(proxyBeanMethods = false)
public class TestFixturesConfiguration {
}
