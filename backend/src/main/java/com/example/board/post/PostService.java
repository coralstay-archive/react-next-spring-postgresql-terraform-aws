package com.example.board.post;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.attachment.AttachmentService;
import com.example.board.auth.AuthUser;
import com.example.board.category.Category;
import com.example.board.category.CategoryRepository;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;
import com.example.board.post.PostResponses.PostDetail;
import com.example.board.post.PostResponses.PostSummary;
import com.example.board.tag.TagService;
import com.example.board.user.User;
import com.example.board.user.UserRepository;

/**
 * 게시글 조회·쓰기 (TASK-8). 빈 이름 {@code postService} 는 {@code @PreAuthorize} 식에서 쓴다.
 *
 * <p>보이는 글(B2): 삭제({@code deleted_at})·숨김({@code hidden_at}) 글은 ADMIN 이 아니면 목록에 없고 상세는 404 다.
 *
 * <p>수정·삭제 인가(B1)는 컨트롤러의 {@code @PreAuthorize("@ownership.canModify(authentication, @postService.ownerOf(#id,
 * authentication))")} 가 한다. {@link #ownerOf} 가 먼저 404(없음·삭제됨·숨김+비ADMIN)를 판정하고, 그다음 작성자|ADMIN 이 아니면
 * 403. 정지 계정·제한 세션·CSRF 는 보안 체인의 공통 필터가 이보다 앞에서 막는다.
 *
 * <p>ADMIN 전용 카테고리({@code categories.admin_only}, 기본 시드 {@code notice})로 쓰거나 옮기는 것은 ADMIN 만(403
 * {@code category_admin_only}).
 */
@Service("postService")
public class PostService {

	public static final String NOT_FOUND = "post_not_found";

	public static final String UNKNOWN_CATEGORY = "unknown_category";

	public static final String CATEGORY_ADMIN_ONLY = "category_admin_only";

	private final PostRepository posts;

	private final CategoryRepository categories;

	private final UserRepository users;

	private final TagService tags;

	private final PostLikeService likes;

	private final AttachmentService attachments;

	public PostService(PostRepository posts, CategoryRepository categories, UserRepository users, TagService tags,
			PostLikeService likes, AttachmentService attachments) {
		this.posts = posts;
		this.categories = categories;
		this.users = users;
		this.tags = tags;
		this.likes = likes;
		this.attachments = attachments;
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
		return PostDetail.of(post, canModify(post, viewer), likes.isLiked(id, viewer), attachments.forPost(id));
	}

	/** 작성 (TASK-8#3). */
	@Transactional
	public PostDetail create(AuthUser writer, PostRequests.Create request) {
		User author = users.findById(writer.id()).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
				"unauthorized", "계정을 찾을 수 없습니다"));
		Category category = categoryFor(request.categorySlug(), writer);
		Post post = Post.write(author, category, request.title().strip(), request.content());
		if (request.tags() != null) {
			post.replaceTags(tags.resolve(request.tags()));
		}
		posts.saveAndFlush(post);
		if (request.attachmentIds() != null) {
			attachments.replaceLinks(post.getId(), author.getId(), writer, request.attachmentIds());
		}
		return PostDetail.of(post, true, false, attachments.forPost(post.getId()));
	}

	/** 부분 수정 (TASK-8#3). 인가는 호출 전에 끝났다(@PreAuthorize). */
	@Transactional
	public PostDetail update(Long id, AuthUser editor, PostRequests.Update request) {
		Post post = modifiable(id, editor);
		if (request.title() != null) {
			post.changeTitle(request.title().strip());
		}
		if (request.content() != null) {
			post.changeContent(request.content());
		}
		if (request.categorySlug() != null) {
			post.changeCategory(categoryFor(request.categorySlug(), editor));
		}
		if (request.tags() != null) {
			post.replaceTags(tags.resolve(request.tags()));
		}
		if (request.attachmentIds() != null) {
			attachments.replaceLinks(id, post.getOwnerId(), editor, request.attachmentIds());
		}
		post.touch(Instant.now());
		posts.flush();
		return PostDetail.of(post, true, likes.isLiked(id, editor), attachments.forPost(id));
	}

	/** soft delete (TASK-8#3). */
	@Transactional
	public void delete(Long id, AuthUser editor) {
		modifiable(id, editor).softDelete(Instant.now());
	}

	/**
	 * {@code @PreAuthorize} 용: 이 사용자가 수정 대상으로 볼 수 있는 글의 작성자 id. 없거나 삭제됐거나 (숨김이고 ADMIN 이 아니면)
	 * 404 {@code post_not_found} 를 던진다 — 권한(403)보다 먼저.
	 */
	@Transactional(readOnly = true)
	public Long ownerOf(Long id, Authentication authentication) {
		AuthUser viewer = authentication != null && authentication.getPrincipal() instanceof AuthUser user ? user : null;
		return posts.findById(id).filter(post -> isModifiableTarget(post, viewer)).orElseThrow(PostService::notFound)
			.getOwnerId();
	}

	private Post modifiable(Long id, AuthUser editor) {
		Post post = posts.findDetailById(id).filter(p -> isModifiableTarget(p, editor)).orElseThrow(PostService::notFound);
		if (!canModify(post, editor)) {
			// @PreAuthorize 를 거치지 않은 호출에 대한 방어. 보통은 여기까지 오지 않는다.
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "권한이 없습니다");
		}
		return post;
	}

	/** 삭제되지 않았고, 보는 사람에게 보이는 글. */
	private static boolean isModifiableTarget(Post post, AuthUser viewer) {
		return !post.isDeleted() && canSee(post, viewer);
	}

	/** slug → 카테고리. 빈 값은 카테고리 없음, 없는 slug 는 400, ADMIN 전용 카테고리는 ADMIN 만. */
	private Category categoryFor(String slug, AuthUser writer) {
		if (slug == null || slug.isBlank()) {
			return null;
		}
		Category category = categories.findBySlug(slug.strip()).orElseThrow(
				() -> new ApiException(HttpStatus.BAD_REQUEST, UNKNOWN_CATEGORY, "없는 카테고리입니다: " + slug));
		if (category.isAdminOnly() && !isAdmin(writer)) {
			throw new ApiException(HttpStatus.FORBIDDEN, CATEGORY_ADMIN_ONLY, "관리자만 쓸 수 있는 카테고리입니다");
		}
		return category;
	}

	public static boolean isAdmin(AuthUser viewer) {
		return viewer != null && viewer.isAdmin();
	}

	/** B2: 보이는 글이거나 ADMIN. */
	public static boolean canSee(Post post, AuthUser viewer) {
		return post.isVisible() || isAdmin(viewer);
	}

	/** 수정·삭제 가능(B1): 삭제되지 않았고, 작성자 또는 ADMIN. */
	static boolean canModify(Post post, AuthUser viewer) {
		return viewer != null && !post.isDeleted() && (viewer.isAdmin() || viewer.id().equals(post.getOwnerId()));
	}

	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "글을 찾을 수 없습니다");
	}

}
