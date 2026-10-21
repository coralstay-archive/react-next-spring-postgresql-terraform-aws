package com.example.board.common;

import java.time.Instant;

/** {@link ClockEntityListener} 가 저장 직전에 Clock 빈의 시각을 넣어 주는 엔티티 (TASK-23#6, BE-7). */
public interface Timestamped {

	/** 처음 저장(INSERT) 직전. */
	void onCreate(Instant now);

	/** 변경 저장(UPDATE) 직전. 기본은 아무것도 하지 않는다. */
	default void onUpdate(Instant now) {
	}

}
