package com.example.board.common;

/**
 * 문자열 입력에서 받지 않는 제어 문자 (TASK-20#2, BE-2). NUL(U+0000)은 PostgreSQL text 에 저장할 수 없어 그대로 두면 500 이 되고,
 * 나머지 C0·DEL·C1 제어 문자도 정상 입력에는 없다. 탭·개행(LF)·CR 은 본문 줄바꿈이므로 받는다.
 */
public final class ControlCharacters {

	public static final String MESSAGE = "쓸 수 없는 제어 문자가 있습니다";

	private ControlCharacters() {
	}

	/** U+0000–U+001F(탭·LF·CR 제외), U+007F–U+009F 가 있으면 true. */
	public static boolean containsForbidden(CharSequence value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c != '\t' && c != '\n' && c != '\r' && Character.isISOControl(c)) {
				return true;
			}
		}
		return false;
	}

}
