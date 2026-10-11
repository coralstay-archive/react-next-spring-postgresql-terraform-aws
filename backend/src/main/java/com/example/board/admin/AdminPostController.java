package com.example.board.admin;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.admin.AdminPostService.AdminPostResponse;
import com.example.board.admin.AdminRequests.ReasonRequest;
import com.example.board.auth.AuthUser;

/** 관리자 글 API (TASK-12#2). 본문 {@code {reason?}} 은 생략할 수 있다. */
@RestController
public class AdminPostController {

	private final AdminPostService service;

	public AdminPostController(AdminPostService service) {
		this.service = service;
	}

	@PostMapping("/admin/posts/{id}/hide")
	public AdminPostResponse hide(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest body,
			@AuthenticationPrincipal AuthUser admin) {
		return service.hide(admin, id, ReasonRequest.of(body));
	}

	@PostMapping("/admin/posts/{id}/unhide")
	public AdminPostResponse unhide(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest body,
			@AuthenticationPrincipal AuthUser admin) {
		return service.unhide(admin, id, ReasonRequest.of(body));
	}

	@PostMapping("/admin/posts/{id}/restore")
	public AdminPostResponse restore(@PathVariable Long id, @Valid @RequestBody(required = false) ReasonRequest body,
			@AuthenticationPrincipal AuthUser admin) {
		return service.restore(admin, id, ReasonRequest.of(body));
	}

}
