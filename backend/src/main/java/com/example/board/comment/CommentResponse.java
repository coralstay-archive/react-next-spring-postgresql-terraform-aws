package com.example.board.comment;

import java.time.Instant;

import com.example.board.post.PostResponses.AuthorRef;

/**
 * 댓글 응답 (TASK-9#1). 삭제된 댓글은 자리표시: {@code deleted=true}, {@code content}·{@code author} 는 null.
 * {@code editable}: 보는 사람이 지울 수 있는지(작성자 또는 ADMIN, 삭제되지 않은 댓글).
 */
public record CommentResponse(Long id, Long postId, String content, AuthorRef author, Instant createdAt,
		Instant updatedAt, boolean deleted, boolean editable) {

	static CommentResponse of(Comment comment, Long postId, boolean editable) {
		if (comment.isDeleted()) {
			return new CommentResponse(comment.getId(), postId, null, null, comment.getCreatedAt(),
					comment.getUpdatedAt(), true, false);
		}
		return new CommentResponse(comment.getId(), postId, comment.getContent(),
				new AuthorRef(comment.getAuthor().getId(), comment.getAuthor().getNickname()), comment.getCreatedAt(),
				comment.getUpdatedAt(), false, editable);
	}

}
