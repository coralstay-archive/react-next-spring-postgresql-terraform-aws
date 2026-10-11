package com.example.board.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 작업(@Scheduled)을 켠다. 예: 미확정 첨부 정리 (TASK-11#4). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {

}
