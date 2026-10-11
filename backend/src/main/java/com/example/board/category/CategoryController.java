package com.example.board.category;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리 목록 (TASK-8#4). 공개. 관리(추가·수정)는 TASK-12. */
@RestController
public class CategoryController {

	/** {@code adminOnly}: ADMIN 만 이 카테고리에 글을 쓸 수 있다(프론트 작성 폼이 USER 에게 숨길 때 쓴다). */
	public record CategoryResponse(Long id, String slug, String name, int sortOrder, boolean adminOnly) {

		static CategoryResponse of(Category category) {
			return new CategoryResponse(category.getId(), category.getSlug(), category.getName(),
					category.getSortOrder(), category.isAdminOnly());
		}

	}

	private final CategoryRepository categories;

	public CategoryController(CategoryRepository categories) {
		this.categories = categories;
	}

	@GetMapping("/categories")
	public List<CategoryResponse> list() {
		return categories.findAllByOrderBySortOrderAscIdAsc().stream().map(CategoryResponse::of).toList();
	}

}
