package com.example.board.common;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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

}
