package com.example.board.post;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;
import com.example.board.post.PostResponses.PostDetail;
import com.example.board.post.PostResponses.PostSummary;

/**
 * 게시글 조회·쓰기 (TASK-8). 빈 이름 {@code postService} 는 {@code @PreAuthorize} 식에서 쓴다.
 *
 * <p>보이는 글(B2): 삭제({@code deleted_at})·숨김({@code hidden_at}) 글은 ADMIN 이 아니면 목록에 없고 상세는 404 다.
 */
@Service("postService")
public class PostService {

	public static final String NOT_FOUND = "post_not_found";

	private final PostRepository posts;

	public PostService(PostRepository posts) {
		this.posts = posts;
	}

	/** 목록. 페이지 조회(+count) 다음에 그 페이지 글들의 작성자·카테고리·태그를 한 쿼리로 붙인다. */
	@Transactional(readOnly = true)
	public PageResponse<PostSummary> list(PostQuery query, int page, int size) {
		Page<Post> result = posts.findAll(PostSpecifications.of(query), PageRequest.of(page, size, query.sort().sort()));
		List<Long> ids = result.getContent().stream().map(Post::getId).toList();
		if (!ids.isEmpty()) {
			Map<Long, Post> loaded = posts.fetchDetails(ids).stream()
				.collect(Collectors.toMap(Post::getId, Function.identity()));
			// 같은 영속성 컨텍스트라 페이지의 엔티티와 같은 인스턴스다. 순서는 페이지 쿼리를 따른다.
			result = result.map(post -> loaded.getOrDefault(post.getId(), post));
		}
		return PageResponse.of(result, PostSummary::of);
	}

	/** 상세. 없거나 보는 사람에게 보이지 않으면 404 {@code post_not_found}. */
	@Transactional(readOnly = true)
	public PostDetail get(Long id, AuthUser viewer) {
		Post post = posts.findDetailById(id).filter(p -> canSee(p, viewer)).orElseThrow(PostService::notFound);
		return PostDetail.of(post, canModify(post, viewer));
	}

	static boolean isAdmin(AuthUser viewer) {
		return viewer != null && viewer.isAdmin();
	}

	/** B2: 보이는 글이거나 ADMIN. */
	static boolean canSee(Post post, AuthUser viewer) {
		return post.isVisible() || isAdmin(viewer);
	}

	/** 수정·삭제 가능(B1): 삭제되지 않았고, 작성자 또는 ADMIN. */
	static boolean canModify(Post post, AuthUser viewer) {
		return viewer != null && !post.isDeleted() && (viewer.isAdmin() || viewer.id().equals(post.getOwnerId()));
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "글을 찾을 수 없습니다");
	}

}
