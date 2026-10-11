package com.example.board.post;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.AuthUser;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;
import com.example.board.post.PostResponses.PostDetail;
import com.example.board.post.PostResponses.PostSummary;
import com.example.board.tag.TagNames;

/** 게시글 API (TASK-8). 목록·상세는 공개(익명 가능, SecurityConfig permitAll). */
@RestController
public class PostController {

	static final int MAX_PAGE_SIZE = 50;

	private final PostService posts;

	public PostController(PostService posts) {
		this.posts = posts;
	}

	/**
	 * 목록 (TASK-8#2). {@code includeHidden=true} 는 ADMIN 일 때만 효과가 있고(삭제·숨김 글 포함), 그 밖에는 무시한다(B2).
	 */
	@GetMapping("/posts")
	public PageResponse<PostSummary> list(@RequestParam(required = false) String category,
			@RequestParam(required = false) String tag, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "latest") String sort,
			@RequestParam(defaultValue = "false") boolean includeHidden, @AuthenticationPrincipal AuthUser viewer) {
		if (page < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "page 는 0 이상이어야 합니다");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다");
		}
		PostQuery query = new PostQuery(blankToNull(category), tag == null || tag.isBlank() ? null : TagNames.normalize(tag),
				includeHidden && PostService.isAdmin(viewer), PostSort.parse(sort));
		return posts.list(query, page, size);
	}

	/** 상세 (TASK-8#2). 삭제·숨김 글은 ADMIN 이 아니면 404(B2). */
	@GetMapping("/posts/{id}")
	public PostDetail get(@PathVariable Long id, @AuthenticationPrincipal AuthUser viewer) {
		return posts.get(id, viewer);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
