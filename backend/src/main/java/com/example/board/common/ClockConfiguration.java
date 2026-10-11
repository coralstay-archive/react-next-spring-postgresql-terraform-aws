package com.example.board.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시각이 필요한 규칙(OTP 만료·발송 한도)이 쓰는 시계. 테스트는 조절 가능한 시계로 바꿔 sleep 없이 확인한다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
