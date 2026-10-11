package com.example.board.attachment;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.attachment.AttachmentResponses.Presigned;
import com.example.board.auth.AuthUser;

/**
 * 첨부 API (TASK-11). 모두 인증 필요(SecurityConfig 기본 규칙) + 세션이면 CSRF + 정지·step-up 쓰기 차단 필터.
 */
@RestController
public class AttachmentController {

	private final AttachmentService attachments;

	public AttachmentController(AttachmentService attachments) {
		this.attachments = attachments;
	}

	/** PUT URL 발급 (TASK-11#1). */
	@PostMapping("/attachments/presign")
	public Presigned presign(@Valid @RequestBody AttachmentRequests.Presign request,
			@AuthenticationPrincipal AuthUser user) {
		return attachments.presign(user, request);
	}

}
