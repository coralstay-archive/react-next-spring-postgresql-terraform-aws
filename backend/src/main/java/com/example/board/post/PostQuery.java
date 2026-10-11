package com.example.board.post;

/**
 * 목록 조건 (TASK-8#2). 필터는 {@link PostSpecifications#of(PostQuery)} 가 JPA Specification 으로 바꾼다.
 *
 * @param categorySlug 카테고리 slug (null 이면 전체)
 * @param tag 정규화된 태그 이름 (null 이면 전체)
 * @param includeHidden 삭제·숨김 글 포함 여부. ADMIN 일 때만 true 가 될 수 있다(B2, 호출자가 판정).
 * @param sort 정렬
 * @param q 검색어(TASK-10#3). 앞뒤 공백을 자른 2~100자, null 이면 검색하지 않는다(호출자가 검증).
 */
public record PostQuery(String categorySlug, String tag, boolean includeHidden, PostSort sort, String q) {

	public static final int MIN_Q = 2;

	public static final int MAX_Q = 100;

}
