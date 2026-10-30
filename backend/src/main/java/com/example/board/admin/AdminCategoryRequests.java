package com.example.board.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 관리자 카테고리 요청 (TASK-12#3). slug 는 URL·필터에 쓰이므로 소문자·숫자·하이픈 2~30자. */
public final class AdminCategoryRequests {

	static final String SLUG = "^[a-z0-9-]{2,30}$";

	static final String SLUG_MESSAGE = "slug 는 소문자·숫자·하이픈 2~30자여야 합니다";

	static final String NOT_BLANK = "(?s).*\\S.*";

	/** sortOrder 범위 (TASK-27#4, BE-40). 카테고리는 많아야 수십 개라 충분하고, "최대 + 1" 이 int 를 넘을 일이 없다. */
	public static final int MIN_SORT_ORDER = 0;

	public static final int MAX_SORT_ORDER = 10000;

	static final String SORT_ORDER_MESSAGE = "순서는 " + MIN_SORT_ORDER + "~" + MAX_SORT_ORDER + " 이어야 합니다";

	private AdminCategoryRequests() {
	}

	/** sortOrder 를 생략하면 맨 뒤(현재 최대 + 1, 상한 {@value #MAX_SORT_ORDER}), adminOnly 를 생략하면 false. */
	public record Create(@NotNull(message = SLUG_MESSAGE) @Pattern(regexp = SLUG, message = SLUG_MESSAGE) String slug,
			@NotNull(message = "이름을 입력하세요") @Size(min = 1, max = 50, message = "이름은 1~50자입니다")
			@Pattern(regexp = NOT_BLANK, message = "이름을 입력하세요") String name,
			@Min(value = MIN_SORT_ORDER, message = SORT_ORDER_MESSAGE) @Max(value = MAX_SORT_ORDER,
					message = SORT_ORDER_MESSAGE) Integer sortOrder,
			Boolean adminOnly) {
	}

	/** 부분 수정: 보낸(=null 이 아닌) 필드만 바꾼다. */
	public record Update(@Pattern(regexp = SLUG, message = SLUG_MESSAGE) String slug,
			@Size(min = 1, max = 50, message = "이름은 1~50자입니다") @Pattern(regexp = NOT_BLANK,
					message = "이름을 입력하세요") String name,
			@Min(value = MIN_SORT_ORDER, message = SORT_ORDER_MESSAGE) @Max(value = MAX_SORT_ORDER,
					message = SORT_ORDER_MESSAGE) Integer sortOrder,
			Boolean adminOnly) {
	}

}
