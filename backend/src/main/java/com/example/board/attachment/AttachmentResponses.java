package com.example.board.attachment;

import java.time.Instant;
import java.util.Map;

/** 첨부 응답 (TASK-11). */
public final class AttachmentResponses {

	private AttachmentResponses() {
	}

	/**
	 * presign 응답. 클라이언트는 {@code uploadUrl} 로 {@code method}(PUT) 하면서 {@code headers} 를 그대로 보낸다. 본문 크기는
	 * 요청한 size 와 같아야 한다(Content-Length 도 서명돼 있다). 업로드 뒤 {@code POST /attachments/{attachmentId}/confirm}.
	 */
	public record Presigned(Long attachmentId, String key, String uploadUrl, String method, Map<String, String> headers,
			Instant expiresAt) {
	}

	/** 첨부 하나. {@code url} 은 공개 주소(운영: CloudFront {@code /uploads/...}). */
	public record AttachmentView(Long id, String url, String contentType, long size, String status) {

		static AttachmentView of(Attachment attachment, UploadProperties properties) {
			return new AttachmentView(attachment.getId(), properties.publicUrl(attachment.getS3Key()),
					attachment.getContentType(), attachment.getSize(), attachment.getStatus().name());
		}

	}

}
