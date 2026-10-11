package com.example.board.comment;

import java.time.Clock;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;
import com.example.board.post.Post;
import com.example.board.post.PostRepository;
import com.example.board.post.PostService;
import com.example.board.user.User;
import com.example.board.user.UserRepository;

/**
 * 댓글 (TASK-9#1). 빈 이름 {@code commentService} 는 {@code @PreAuthorize} 식에서 쓴다.
 *
 * <ul>
 * <li>목록: 글이 보는 사람에게 보여야 한다({@link PostService#canSee}, 아니면 404 {@code post_not_found}). 오래된 순.
 * 삭제된 댓글은 자리표시로 남는다({@link CommentResponse}).</li>
 * <li>작성(B3): 삭제·숨김 글에는 누구도(ADMIN 포함) 쓸 수 없다 → 404 {@code post_not_found}. 글 행을 FOR SHARE 로 읽어
 * 확인과 삽입 사이에 글이 삭제되지 않게 한다.</li>
 * <li>삭제: 작성자 또는 ADMIN({@code @ownership}), soft delete. 없거나 이미 삭제됐거나 글이 보이지 않으면 404
 * {@code comment_not_found}(권한 403 보다 먼저).</li>
 * </ul>
 */
@Service("commentService")
public class CommentService {

	public static final String NOT_FOUND = "comment_not_found";

	private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	private final CommentRepository comments;

	private final PostRepository posts;

	private final UserRepository users;

	private final Clock clock;

	public CommentService(CommentRepository comments, PostRepository posts, UserRepository users, Clock clock) {
		this.comments = comments;
		this.posts = posts;
		this.users = users;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PageResponse<CommentResponse> list(Long postId, AuthUser viewer, int page, int size) {
		posts.findById(postId).filter(post -> PostService.canSee(post, viewer)).orElseThrow(PostService::notFound);
		Page<Comment> result = comments.findPageByPostId(postId, PageRequest.of(page, size, OLDEST_FIRST));
		return PageResponse.of(result, comment -> CommentResponse.of(comment, postId, canModify(comment, viewer)));
	}

	@Transactional
	public CommentResponse create(Long postId, AuthUser writer, CommentRequest request) {
		// B3: 삭제·숨김 글에는 쓸 수 없다(ADMIN 포함).
		Post post = posts.findForShareById(postId).filter(Post::isVisible).orElseThrow(PostService::notFound);
		User author = users.findById(writer.id())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "계정을 찾을 수 없습니다"));
		Comment comment = comments.saveAndFlush(Comment.write(post, author, request.content(), clock.instant()));
		return CommentResponse.of(comment, postId, true);
	}

	@Transactional
	public void delete(Long id, AuthUser editor) {
		Comment comment = target(id, editor);
		if (!canModify(comment, editor)) {
			// @PreAuthorize 를 거치지 않은 호출에 대한 방어
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "권한이 없습니다");
		}
		comment.softDelete(clock.instant());
	}

	/** {@code @PreAuthorize} 용: 지울 수 있는 대상 댓글의 작성자 id. 대상이 아니면 404 {@code comment_not_found}. */
	@Transactional(readOnly = true)
	public Long ownerOf(Long id, Authentication authentication) {
		AuthUser viewer = authentication != null && authentication.getPrincipal() instanceof AuthUser user ? user : null;
		return target(id, viewer).getOwnerId();
	}

	/** 삭제되지 않았고, 글이 보는 사람에게 보이는 댓글. */
	private Comment target(Long id, AuthUser viewer) {
		return comments.findWithPostById(id)
			.filter(comment -> !comment.isDeleted() && PostService.canSee(comment.getPost(), viewer))
			.orElseThrow(CommentService::notFound);
	}

	private static boolean canModify(Comment comment, AuthUser viewer) {
		return viewer != null && !comment.isDeleted() && (viewer.isAdmin() || viewer.id().equals(comment.getOwnerId()));
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "댓글을 찾을 수 없습니다");
	}

}
