package com.example.board.admin;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.admin.AdminRequests.ReasonRequest;
import com.example.board.admin.AdminUserService.AdminUserResponse;
import com.example.board.auth.AuthUser;
import com.example.board.common.PageResponse;
import com.example.board.common.Paging;
import com.example.board.user.UserStatus;

/**
 * 관리자 사용자 API (TASK-12#1). {@code /api/admin/**} 는 보안 체인에서 ADMIN 만 통과한다(B1). 쓰기는 다른 쓰기와 같이 CSRF·정지·step-up
 * 필터를 거친다.
 */
@RestController
public class AdminUserController {

	static final int MAX_PAGE_SIZE = 100;

	private final AdminUserService service;

	public AdminUserController(AdminUserService service) {
		this.service = service;
	}

	@GetMapping("/admin/users")
	public PageResponse<AdminUserResponse> list(@RequestParam(required = false) String q,
			@RequestParam(required = false) UserStatus status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		checkPage(page, size);
		return service.list(q, status, page, size);
	}

	@PostMapping("/admin/users/{id}/suspend")
	public AdminUserResponse suspend(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest body,
			@AuthenticationPrincipal AuthUser admin) {
		return service.suspend(admin, id, ReasonRequest.of(body));
	}

	@PostMapping("/admin/users/{id}/unsuspend")
	public AdminUserResponse unsuspend(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest body,
			@AuthenticationPrincipal AuthUser admin) {
		return service.unsuspend(admin, id, ReasonRequest.of(body));
	}

	static void checkPage(int page, int size) {
		Paging.check(page, size, MAX_PAGE_SIZE);
	}

}
