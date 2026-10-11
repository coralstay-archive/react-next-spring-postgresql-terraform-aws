package com.example.board.post;

/**
 * 목록 조건 (TASK-8#2). 필터는 {@link PostSpecifications#of(PostQuery)} 가 JPA Specification 으로 바꾼다.
 * 검색(TASK-10)은 여기에 {@code q} 를 더하고 PostSpecifications 에 조건 하나를 추가하면 된다.
 *
 * @param categorySlug 카테고리 slug (null 이면 전체)
 * @param tag 정규화된 태그 이름 (null 이면 전체)
 * @param includeHidden 삭제·숨김 글 포함 여부. ADMIN 일 때만 true 가 될 수 있다(B2, 호출자가 판정).
 * @param sort 정렬
 */
public record PostQuery(String categorySlug, String tag, boolean includeHidden, PostSort sort) {

}
