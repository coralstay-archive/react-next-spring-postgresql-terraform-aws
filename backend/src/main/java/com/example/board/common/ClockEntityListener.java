package com.example.board.common;

import java.time.Clock;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * 엔티티 생성·수정 시각을 Clock 빈에서 넣는다 (TASK-23#6, BE-7). 예전에는 엔티티의 {@code @PrePersist} 가 시스템 시계를
 * 읽어서 서비스의 Clock(테스트의 MutableClock)과 따로 놀았다. Hibernate 는 Boot 가 설정한 SpringBeanContainer 로 이 리스너를
 * 만들므로 생성자에 Clock 빈이 주입된다.
 */
public class ClockEntityListener {

	private final Clock clock;

	public ClockEntityListener(Clock clock) {
		this.clock = clock;
	}

	@PrePersist
	void prePersist(Object entity) {
		if (entity instanceof Timestamped timestamped) {
			timestamped.onCreate(clock.instant());
		}
	}

	@PreUpdate
	void preUpdate(Object entity) {
		if (entity instanceof Timestamped timestamped) {
			timestamped.onUpdate(clock.instant());
		}
	}

}
