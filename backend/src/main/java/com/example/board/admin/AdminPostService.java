package com.example.board.admin;

import java.time.Clock;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.common.ApiException;
import com.example.board.post.Post;
import com.example.board.post.PostRepository;

/**
 * 관리자 글 관리 (TASK-12#2): 숨김({@code hidden_at})·숨김 해제·삭제 복구({@code deleted_at} 해제). 모두 멱등이고, 호출마다 감사 로그를
 * 남긴다(상태가 이미 같아도 관리자가 한 일로 기록). 숨김과 삭제는 독립이라 복구해도 숨김은 유지된다.
 */
@Service
public class AdminPostService {

	private final PostRepository posts;

	private final AdminAudit audit;

	private final Clock clock;

	public AdminPostService(PostRepository posts, AdminAudit audit, Clock clock) {
		this.posts = posts;
		this.audit = audit;
		this.clock = clock;
	}

	/** 관리 액션 뒤 글 상태. */
	public record AdminPostResponse(Long id, boolean hidden, boolean deleted, Instant hiddenAt, Instant deletedAt) {

		static AdminPostResponse of(Post post) {
			return new AdminPostResponse(post.getId(), post.isHidden(), post.isDeleted(), post.getHiddenAt(),
					post.getDeletedAt());
		}

	}

	@Transactional
	public AdminPostResponse hide(AuthUser admin, Long postId, String reason) {
		Post post = find(postId);
		post.hide(clock.instant());
		return done(admin, AdminAction.Type.POST_HIDE, post, reason);
	}

	@Transactional
	public AdminPostResponse unhide(AuthUser admin, Long postId, String reason) {
		Post post = find(postId);
		post.unhide();
		return done(admin, AdminAction.Type.POST_UNHIDE, post, reason);
	}

	@Transactional
	public AdminPostResponse restore(AuthUser admin, Long postId, String reason) {
		Post post = find(postId);
		post.restore();
		return done(admin, AdminAction.Type.POST_RESTORE, post, reason);
	}

	private Post find(Long postId) {
		return posts.findById(postId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "post_not_found", "글을 찾을 수 없습니다"));
	}

	private AdminPostResponse done(AuthUser admin, AdminAction.Type action, Post post, String reason) {
		audit.record(admin.id(), action, post.getId(), reason);
		return AdminPostResponse.of(posts.saveAndFlush(post));
	}

}
