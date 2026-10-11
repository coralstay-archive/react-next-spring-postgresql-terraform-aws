package com.example.board.post;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.AuthUser;
import com.example.board.post.PostLikeService.LikeResponse;

/** 좋아요 API (TASK-9#2). 인증 필요(세션이면 CSRF), 정지·제한 세션은 공통 필터가 막는다. 둘 다 멱등이고 200. */
@RestController
public class PostLikeController {

	private final PostLikeService likes;

	public PostLikeController(PostLikeService likes) {
		this.likes = likes;
	}

	@PostMapping("/posts/{id}/like")
	public LikeResponse like(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
		return likes.like(id, user);
	}

	@DeleteMapping("/posts/{id}/like")
	public LikeResponse unlike(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
		return likes.unlike(id, user);
	}

}
