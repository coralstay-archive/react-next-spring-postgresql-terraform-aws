package com.example.board.comment;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.example.board.auth.AuthUser;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;

/**
 * 댓글 API (TASK-9#1). 목록은 공개(SecurityConfig permitAll), 작성·삭제는 인증 + (세션이면) CSRF, 정지·제한 세션은 공통 필터가
 * 막는다.
 */
@RestController
public class CommentController {

	static final int MAX_PAGE_SIZE = 100;

	private final CommentService comments;

	public CommentController(CommentService comments) {
		this.comments = comments;
	}

	@GetMapping("/posts/{postId}/comments")
	public PageResponse<CommentResponse> list(@PathVariable Long postId, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal AuthUser viewer) {
		if (page < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "page 는 0 이상이어야 합니다");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다");
		}
		return comments.list(postId, viewer, page, size);
	}

	/** 작성. 201 + Location({@code /api/comments/{id}}) + 댓글. */
	@PostMapping("/posts/{postId}/comments")
	public ResponseEntity<CommentResponse> create(@PathVariable Long postId, @Valid @RequestBody CommentRequest request,
			@AuthenticationPrincipal AuthUser user) {
		CommentResponse created = comments.create(postId, user, request);
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/comments/{id}")
			.buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	/** soft delete. 204. 작성자 또는 ADMIN. */
	@DeleteMapping("/comments/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @commentService.ownerOf(#id, authentication))")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
		comments.delete(id, user);
	}

}
