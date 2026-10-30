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
import com.example.board.common.SqlErrors;

/**
 * 관리자 카테고리 관리 (TASK-12#3). slug 중복은 409 {@code slug_taken}: 먼저 존재 여부를 보고, 동시에 같은 slug 를 만드는 경쟁은
 * {@code categories_slug_uk} 유니크 위반을 같은 409 로 바꾼다. 삭제하면 그 카테고리의 글은 FK {@code ON DELETE SET NULL} 로
 * "카테고리 없음"이 된다(글은 남는다).
 */
@Service
public class AdminCategoryService {

	public static final String SLUG_TAKEN = "slug_taken";

	/** V5 의 slug 유니크 제약 이름. */
	static final String SLUG_UNIQUE = "categories_slug_uk";

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

	/**
	 * 맨 뒤 = 현재 최대 + 1. 상한({@link AdminCategoryRequests#MAX_SORT_ORDER})을 넘지 않는다(TASK-27#4, BE-40) — 상한에 닿으면 같은 값이
	 * 여럿일 수 있다(유일 제약이 없고 목록은 sort_order 다음 id 순). 검증 전에 들어간 범위 밖 값(int 최댓값 등)이 있어도 long 으로 더해서
	 * 오버플로(음수)가 나지 않는다.
	 */
	private int nextSortOrder() {
		Integer max = categories.findMaxSortOrder();
		if (max == null) {
			return AdminCategoryRequests.MIN_SORT_ORDER;
		}
		long next = Math.max((long) max + 1, AdminCategoryRequests.MIN_SORT_ORDER);
		return (int) Math.min(next, AdminCategoryRequests.MAX_SORT_ORDER);
	}

	private Category save(Category category) {
		try {
			return categories.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			// slug 유니크 위반(동시에 같은 slug 를 만드는 경쟁)만 slug_taken. 나머지는 공통 매핑(ApiExceptionHandler, TASK-20#5)
			if (SqlErrors.violates(ex, SLUG_UNIQUE)) {
				throw slugTaken();
			}
			throw ex;
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
