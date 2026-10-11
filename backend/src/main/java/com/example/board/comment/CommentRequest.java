package com.example.board.comment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 댓글 작성 요청 (TASK-9#1). 본문 1~2000자, 공백만은 안 된다. */
public record CommentRequest(
		@NotNull(message = "댓글을 입력하세요") @Size(min = 1, max = CommentRequest.MAX_LENGTH, message = "댓글은 1~2000자입니다")
		@Pattern(regexp = "(?s).*\\S.*", message = "댓글을 입력하세요") String content) {

	public static final int MAX_LENGTH = 2000;

}
