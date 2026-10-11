package com.example.board.auth.local;

import java.util.Locale;

/** LOCAL 로그인 키(이메일) 규칙. */
public final class Emails {

	/** {@code @Email} 기본 검사(로컬@도메인)에 더해 공백 없이, 도메인에 점이 하나 이상 있어야 한다. */
	public static final String SHAPE = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

	private Emails() {
	}

	/**
	 * LOCAL identity 의 subject 로 쓰는 정규화: 앞뒤 공백 제거 + 소문자(Locale.ROOT). 대소문자만 다른 이메일은 같은 계정이다
	 * (TASK-3 B1).
	 */
	public static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

}
