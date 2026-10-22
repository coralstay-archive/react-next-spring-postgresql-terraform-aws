package com.example.board.common;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

import tools.jackson.databind.json.JsonMapper;

/** 모든 필터(Spring Session·보안 체인)보다 바깥에 두는 공통 필터 (TASK-23). */
@Configuration(proxyBeanMethods = false)
public class FilterConfiguration {

	@Bean
	FilterRegistrationBean<DbFailureFilter> dbFailureFilter(JsonMapper json) {
		FilterRegistrationBean<DbFailureFilter> registration = new FilterRegistrationBean<>(new DbFailureFilter(json));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
		return registration;
	}

	/** 본문 크기 상한 (TASK-23#2). 보안 체인보다 앞이라 인증 전 요청(로그인 등)도 큰 본문을 읽지 않는다. */
	@Bean
	FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
			@Value("${app.request.max-body-size:64KB}") DataSize maxBodySize, JsonMapper json) {
		FilterRegistrationBean<RequestBodyLimitFilter> registration = new FilterRegistrationBean<>(
				new RequestBodyLimitFilter(maxBodySize.toBytes(), json));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
		return registration;
	}

}
