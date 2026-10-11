package com.example.board.attachment;

import java.time.Clock;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.attachment.AttachmentResponses.Presigned;
import com.example.board.attachment.UploadStorage.PresignedUpload;
import com.example.board.common.ApiException;

/**
 * 이미지 첨부 (TASK-11). 업로드 본문은 앱을 거치지 않는다: presign(서명된 PUT URL) → 클라이언트가 S3 에 직접 PUT → confirm.
 */
@Service
public class AttachmentService {

	public static final String TYPE_NOT_ALLOWED = "attachment_type_not_allowed";

	public static final String TOO_LARGE = "attachment_too_large";

	private final AttachmentRepository attachments;

	private final UploadStorage storage;

	private final UploadProperties properties;

	private final Clock clock;

	public AttachmentService(AttachmentRepository attachments, UploadStorage storage, UploadProperties properties,
			Clock clock) {
		this.attachments = attachments;
		this.storage = storage;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * PUT URL 발급 (TASK-11#1). B1: 허용 타입(png·jpeg·webp·gif)·5MB 이하만. 키는 {@code uploads/{userId}/{uuid}.{ext}} — 확장자는
	 * 타입에서 정하고 파일명은 쓰지 않는다. 행은 PENDING 으로 남고 confirm 하지 않으면 정리 대상이 된다.
	 */
	@Transactional
	public Presigned presign(AuthUser user, AttachmentRequests.Presign request) {
		String contentType = ImageTypes.normalize(request.contentType());
		String extension = ImageTypes.extension(contentType)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, TYPE_NOT_ALLOWED,
					"이미지(png, jpeg, webp, gif)만 올릴 수 있습니다"));
		long size = request.size();
		if (size > ImageTypes.MAX_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, TOO_LARGE, "5MB 이하만 올릴 수 있습니다");
		}
		if (!storage.available()) {
			throw DisabledUploadStorage.unavailable();
		}
		String key = UploadProperties.KEY_PREFIX + user.id() + "/" + UUID.randomUUID() + "." + extension;
		Attachment attachment = attachments.saveAndFlush(Attachment.pending(user.id(), key, contentType, size, clock.instant()));
		PresignedUpload upload = storage.presignPut(key, contentType, size, properties.presignTtl());
		return new Presigned(attachment.getId(), key, upload.url(), "PUT", upload.headers(), upload.expiresAt());
	}

}
