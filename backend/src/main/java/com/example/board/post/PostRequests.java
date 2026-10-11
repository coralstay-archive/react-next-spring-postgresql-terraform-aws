package com.example.board.post;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 게시글 쓰기 요청 (TASK-8#3). */
public final class PostRequests {

	/** 공백만으로 된 값은 안 된다. */
	static final String NOT_BLANK = "(?s).*\\S.*";

	/** 정규화 전 태그 입력 개수 상한(남용 방지). 실제 한도는 중복 제거 후 5개(B3). */
	static final int MAX_RAW_TAGS = 20;

	private PostRequests() {
	}

	/**
	 * 작성. {@code categorySlug} 생략 시 카테고리 없음. 태그는 정규화·중복 제거 후 최대 5개(B3).
	 */
	public record Create(
			@NotNull(message = "제목을 입력하세요") @Size(min = 1, max = 100, message = "제목은 1~100자입니다")
			@Pattern(regexp = NOT_BLANK, message = "제목을 입력하세요") String title,
			@NotNull(message = "본문을 입력하세요") @Size(min = 1, max = 20000, message = "본문은 1~20000자입니다")
			@Pattern(regexp = NOT_BLANK, message = "본문을 입력하세요") String content,
			String categorySlug,
			@Size(max = MAX_RAW_TAGS, message = "태그가 너무 많습니다") List<String> tags) {
	}

	/**
	 * 부분 수정. null(생략)인 필드는 그대로 둔다. {@code categorySlug: ""} 는 카테고리를 빼고, {@code tags: []} 는 태그를 비운다.
	 */
	public record Update(
			@Size(min = 1, max = 100, message = "제목은 1~100자입니다") @Pattern(regexp = NOT_BLANK, message = "제목을 입력하세요") String title,
			@Size(min = 1, max = 20000, message = "본문은 1~20000자입니다") @Pattern(regexp = NOT_BLANK, message = "본문을 입력하세요") String content,
			String categorySlug,
			@Size(max = MAX_RAW_TAGS, message = "태그가 너무 많습니다") List<String> tags) {
	}

}
