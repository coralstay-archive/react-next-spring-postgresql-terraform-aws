package com.example.board.admin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.AuthUser;
import com.example.board.category.CategoryController.CategoryResponse;

/** 관리자 카테고리 API (TASK-12#3). 응답 모양은 공개 {@code GET /api/categories} 의 항목과 같다. */
@RestController
public class AdminCategoryController {

	private final AdminCategoryService service;

	public AdminCategoryController(AdminCategoryService service) {
		this.service = service;
	}

	@PostMapping("/admin/categories")
	@ResponseStatus(HttpStatus.CREATED)
	public CategoryResponse create(@Valid @RequestBody AdminCategoryRequests.Create request,
			@AuthenticationPrincipal AuthUser admin) {
		return service.create(admin, request);
	}

	@PatchMapping("/admin/categories/{id}")
	public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody AdminCategoryRequests.Update request,
			@AuthenticationPrincipal AuthUser admin) {
		return service.update(admin, id, request);
	}

	@DeleteMapping("/admin/categories/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthUser admin) {
		service.delete(admin, id);
	}

}
