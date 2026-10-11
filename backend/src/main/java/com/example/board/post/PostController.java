package com.example.board.post;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import com.example.board.post.PostResponses.PostDetail;
import com.example.board.post.PostResponses.PostSummary;
import com.example.board.tag.TagNames;

/**
 * 게시글 API (TASK-8). 목록·상세는 공개(익명 가능, SecurityConfig permitAll). 쓰기는 인증 필요(세션이면 CSRF), 정지 계정·제한 세션은
 * 보안 체인의 공통 필터가 403 으로 막는다.
 */
@RestController
public class PostController {

	static final int MAX_PAGE_SIZE = 50;

	private final PostService posts;

	private final PostViewService views;

	private final ViewerKeys viewerKeys;

	public PostController(PostService posts, PostViewService views, ViewerKeys viewerKeys) {
		this.posts = posts;
		this.views = views;
		this.viewerKeys = viewerKeys;
	}

	/**
	 * 목록 (TASK-8#2). {@code includeHidden=true} 는 ADMIN 일 때만 효과가 있고(삭제·숨김 글 포함), 그 밖에는 무시한다(B2).
	 * {@code q} 는 제목·본문 검색(TASK-10#3)이고 category·tag·sort 와 AND 로 조합된다.
	 */
	@GetMapping("/posts")
	public PageResponse<PostSummary> list(@RequestParam(required = false) String category,
			@RequestParam(required = false) String tag, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "latest") String sort,
			@RequestParam(defaultValue = "false") boolean includeHidden, @RequestParam(required = false) String q,
			@AuthenticationPrincipal AuthUser viewer) {
		if (page < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "page 는 0 이상이어야 합니다");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "size 는 1~" + MAX_PAGE_SIZE + " 이어야 합니다");
		}
		PostQuery query = new PostQuery(blankToNull(category), tag == null || tag.isBlank() ? null : TagNames.normalize(tag),
				includeHidden && PostService.isAdmin(viewer), PostSort.parse(sort), searchTerm(q));
		return posts.list(query, page, size);
	}

	/**
	 * 상세 (TASK-8#2). 삭제·숨김 글은 ADMIN 이 아니면 404(B2).
	 *
	 * <p>조회수 (TASK-9#3): 읽기 트랜잭션이 끝난 뒤 별도 트랜잭션에서 집계한다. 보이는 글(삭제·숨김 아님)만 세고, 같은 방문자는 30분에
	 * 1회(TASK-9 B2, {@link PostViewService}). 익명 방문자에게는 세션 대신 VID 쿠키를 준다({@link ViewerKeys}).
	 */
	@GetMapping("/posts/{id}")
	public PostDetail get(@PathVariable Long id, @AuthenticationPrincipal AuthUser viewer, HttpServletRequest request,
			HttpServletResponse response) {
		PostDetail detail = posts.get(id, viewer);
		if (detail.deleted() || detail.hidden()) {
			return detail;
		}
		boolean counted = views.record(id, viewerKeys.resolve(viewer, request, response));
		return counted ? detail.withCountedView() : detail;
	}

	/** 작성 (TASK-8#3). 201 + Location + 상세. */
	@PostMapping("/posts")
	public ResponseEntity<PostDetail> create(@Valid @RequestBody PostRequests.Create request,
			@AuthenticationPrincipal AuthUser user) {
		PostDetail created = posts.create(user, request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri();
		return ResponseEntity.created(location).body(created);
	}

	/** 부분 수정 (TASK-8#3). 작성자 또는 ADMIN(B1): 없으면 404, 남의 글이면 403. */
	@PatchMapping("/posts/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @postService.ownerOf(#id, authentication))")
	public PostDetail update(@PathVariable Long id, @Valid @RequestBody PostRequests.Update request,
			@AuthenticationPrincipal AuthUser user) {
		return posts.update(id, user, request);
	}

	/** soft delete (TASK-8#3). 204. 작성자 또는 ADMIN(B1). */
	@DeleteMapping("/posts/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @postService.ownerOf(#id, authentication))")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
		posts.delete(id, user);
	}

	/** 검색어 (TASK-10#3): 앞뒤 공백을 자르고, 비었으면 검색하지 않는다. 자른 뒤 2~100자(코드 포인트)가 아니면 400. */
	static String searchTerm(String q) {
		String term = blankToNull(q);
		if (term == null) {
			return null;
		}
		int length = term.codePointCount(0, term.length());
		if (length < PostQuery.MIN_Q || length > PostQuery.MAX_Q) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation",
					"검색어는 " + PostQuery.MIN_Q + "~" + PostQuery.MAX_Q + "자여야 합니다");
		}
		return term;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
