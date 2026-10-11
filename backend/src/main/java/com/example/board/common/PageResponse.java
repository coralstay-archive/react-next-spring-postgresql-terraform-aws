package com.example.board.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/** 오프셋 페이지 응답 {@code {items, page, size, totalElements, totalPages}} (TASK-8#2). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

	public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements(), page.getTotalPages());
	}

}
