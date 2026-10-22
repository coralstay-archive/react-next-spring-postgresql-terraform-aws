package com.example.board.common;

/**
 * 오프셋 페이지 파라미터 검사 (TASK-8·9·12, TASK-23#5 BE-30). 오류는 모두 400 {@code validation} + {@code fields}(파라미터 이름).
 * {@code page × size}(OFFSET)는 {@link #MAX_OFFSET} 이하 — 공개 목록의 깊은 OFFSET 스캔(건너뛸 행을 다 읽는다)을 막는다.
 */
public final class Paging {

	/** OFFSET 상한(행). */
	public static final long MAX_OFFSET = 10_000;

	private Paging() {
	}

	public static void check(int page, int size, int maxSize) {
		if (page < 0) {
			throw ApiErrors.invalidParam("page", "page 는 0 이상이어야 합니다");
		}
		if (size < 1 || size > maxSize) {
			throw ApiErrors.invalidParam("size", "size 는 1~" + maxSize + " 이어야 합니다");
		}
		// long 곱이라 page 가 int 최댓값이어도 넘치지 않는다
		if ((long) page * size > MAX_OFFSET) {
			throw ApiErrors.invalidParam("page", "page × size 는 " + MAX_OFFSET + " 이하여야 합니다");
		}
	}

}
