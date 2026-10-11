package com.example.board.post;

import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** 목록 정렬. 지금은 최신순만. TASK-9 가 {@code popular}(like_count) 를 더한다. */
public enum PostSort {

	/** 최신순: (created_at DESC, id DESC). 부분 인덱스 posts_visible_latest_ix 와 같은 순서. */
	LATEST(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

	private final Sort sort;

	PostSort(Sort sort) {
		this.sort = sort;
	}

	public Sort sort() {
		return sort;
	}

	public static PostSort parse(String value) {
		if (value == null || value.isBlank()) {
			return LATEST;
		}
		try {
			return valueOf(value.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "지원하지 않는 정렬입니다: " + value);
		}
	}

}
