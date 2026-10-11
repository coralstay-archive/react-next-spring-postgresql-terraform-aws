package com.example.board.user;

/** 계정 역할. 권한 문자열은 {@code ROLE_} 접두사를 붙여 쓴다. */
public enum Role {
	USER, ADMIN;

	public String authority() {
		return "ROLE_" + name();
	}
}
