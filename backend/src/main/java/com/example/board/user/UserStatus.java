package com.example.board.user;

/** 계정 상태. SUSPENDED 면 쓰기 요청이 모두 403 이다 (TASK-2 B3). */
public enum UserStatus {
	ACTIVE, SUSPENDED
}
