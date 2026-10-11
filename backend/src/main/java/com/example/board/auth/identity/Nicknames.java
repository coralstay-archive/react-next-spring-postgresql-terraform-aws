package com.example.board.auth.identity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 제공자 이름·로그인 아이디로 닉네임을 만든다. 가입 규칙(2~20자, 앞뒤 공백 없음)을 항상 만족시킨다.
 * 제어 문자를 빼고 연속 공백을 하나로 줄인 뒤 20자(코드 포인트)로 자른다. 2자가 안 되면 {@code user1234} 꼴로 만든다.
 * 닉네임은 유일하지 않아도 된다(표시용).
 */
public final class Nicknames {

	public static final int MIN = 2;

	public static final int MAX = 20;

	private Nicknames() {
	}

	public static String from(String candidate) {
		String cleaned = candidate == null ? "" : candidate.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
		int[] codePoints = cleaned.codePoints().limit(MAX).toArray();
		String nickname = new String(codePoints, 0, codePoints.length).strip();
		if (nickname.codePointCount(0, nickname.length()) < MIN) {
			return "user" + ThreadLocalRandom.current().nextInt(1000, 10000);
		}
		return nickname;
	}

}
