package com.example.board.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.category.Category;
import com.example.board.category.CategoryController.CategoryResponse;
import com.example.board.category.CategoryRepository;
import com.example.board.common.ApiException;

/**
 * 관리자 카테고리 관리 (TASK-12#3). slug 중복은 409 {@code slug_taken}: 먼저 존재 여부를 보고, 동시에 같은 slug 를 만드는 경쟁은
 * {@code categories_slug_uk} 유니크 위반을 같은 409 로 바꾼다. 삭제하면 그 카테고리의 글은 FK {@code ON DELETE SET NULL} 로
 * "카테고리 없음"이 된다(글은 남는다).
 */
@Service
public class AdminCategoryService {

	public static final String SLUG_TAKEN = "slug_taken";

	private final CategoryRepository categories;

	private final AdminAudit audit;

	public AdminCategoryService(CategoryRepository categories, AdminAudit audit) {
		this.categories = categories;
		this.audit = audit;
	}

	@Transactional
	public CategoryResponse create(AuthUser admin, AdminCategoryRequests.Create request) {
		if (categories.existsBySlug(request.slug())) {
			throw slugTaken();
		}
		int sortOrder = request.sortOrder() != null ? request.sortOrder() : nextSortOrder();
		Category category = save(Category.create(request.slug(), request.name().strip(), sortOrder,
				Boolean.TRUE.equals(request.adminOnly())));
		audit.record(admin.id(), AdminAction.Type.CATEGORY_CREATE, category.getId(), null);
		return CategoryResponse.of(category);
	}

	@Transactional
	public CategoryResponse update(AuthUser admin, Long id, AdminCategoryRequests.Update request) {
		Category category = find(id);
		if (request.slug() != null && !request.slug().equals(category.getSlug())
				&& categories.existsBySlug(request.slug())) {
			throw slugTaken();
		}
		category.update(request.slug(), request.name() == null ? null : request.name().strip(), request.sortOrder(),
				request.adminOnly());
		save(category);
		audit.record(admin.id(), AdminAction.Type.CATEGORY_UPDATE, category.getId(), null);
		return CategoryResponse.of(category);
	}

	@Transactional
	public void delete(AuthUser admin, Long id) {
		Category category = find(id);
		categories.delete(category);
		categories.flush();
		audit.record(admin.id(), AdminAction.Type.CATEGORY_DELETE, id, "slug=" + category.getSlug());
	}

	private int nextSortOrder() {
		Integer max = categories.findMaxSortOrder();
		return max == null ? 0 : max + 1;
	}

	private Category save(Category category) {
		try {
			return categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			throw slugTaken();
		}
	}

	private Category find(Long id) {
		return categories.findById(id)
			.orElseThrow(
					() -> new ApiException(HttpStatus.NOT_FOUND, "category_not_found", "카테고리를 찾을 수 없습니다"));
	}

	private static ApiException slugTaken() {
		return new ApiException(HttpStatus.CONFLICT, SLUG_TAKEN, "이미 쓰는 slug 입니다");
	}

}
