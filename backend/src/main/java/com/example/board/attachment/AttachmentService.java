package com.example.board.attachment;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.AuthUser;
import com.example.board.attachment.AttachmentResponses.AttachmentView;
import com.example.board.attachment.AttachmentResponses.Presigned;
import com.example.board.attachment.UploadStorage.PresignedUpload;
import com.example.board.attachment.UploadStorage.StoredObject;
import com.example.board.common.ApiException;

/**
 * 이미지 첨부 (TASK-11). 업로드 본문은 앱을 거치지 않는다: presign(서명된 PUT URL) → 클라이언트가 S3 에 직접 PUT → confirm.
 */
@Service
public class AttachmentService {

	public static final String TYPE_NOT_ALLOWED = "attachment_type_not_allowed";

	public static final String TOO_LARGE = "attachment_too_large";

	public static final String NOT_FOUND = "attachment_not_found";

	public static final String FORBIDDEN = "attachment_forbidden";

	public static final String NOT_UPLOADED = "attachment_not_uploaded";

	public static final String MISMATCH = "attachment_mismatch";

	public static final String UNKNOWN = "unknown_attachment";

	public static final String NOT_CONFIRMED = "attachment_not_confirmed";

	public static final String IN_USE = "attachment_in_use";

	/** 글 하나에 붙일 수 있는 첨부 수. */
	public static final int MAX_PER_POST = 10;

	private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

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

	/**
	 * 업로드 확정 (TASK-11#2). 본인만(ADMIN 도 남의 첨부는 403). HeadObject 로 객체가 있는지, 크기·타입이 발급 때와 같은지(5MB
	 * 이하) 다시 확인한 뒤 {@code status=pending} 태그를 지우고(lifecycle 대상에서 빠짐) CONFIRMED 로 바꾼다. 이미 확정됐으면 그대로
	 * 200(멱등). 다르면 객체를 지우고 400 {@code attachment_mismatch}.
	 *
	 * <p>S3 호출이 DB 트랜잭션을 잡고 있지 않도록 트랜잭션 없이 돌고, 마지막 상태 변경만 조건부 UPDATE 로 한다.
	 */
	public AttachmentView confirm(Long id, AuthUser user) {
		Attachment attachment = attachments.findById(id).orElseThrow(AttachmentService::notFound);
		if (!attachment.getOwnerId().equals(user.id())) {
			throw forbidden();
		}
		if (attachment.isConfirmed()) {
			return AttachmentView.of(attachment, properties);
		}
		if (!storage.available()) {
			throw DisabledUploadStorage.unavailable();
		}
		String key = attachment.getS3Key();
		StoredObject stored = storage.head(key)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, NOT_UPLOADED, "아직 업로드되지 않았습니다"));
		if (!matches(attachment, stored)) {
			deleteQuietly(key);
			throw new ApiException(HttpStatus.BAD_REQUEST, MISMATCH, "업로드된 파일이 요청한 타입·크기와 다릅니다");
		}
		storage.clearPendingTag(key);
		attachments.markConfirmed(id, clock.instant());
		return attachments.findById(id).map(a -> AttachmentView.of(a, properties))
			.orElseThrow(AttachmentService::notFound);
	}

	/**
	 * 글의 첨부를 {@code requestedIds} 로 바꾼다 (TASK-11#2, B2). 글 작성·수정 트랜잭션 안에서 부른다. 이미 이 글에 붙은 것은 그대로
	 * 두고(ADMIN 이 남의 글을 고칠 때도 유지·해제 가능), 새로 붙이는 것은
	 * <ul>
	 * <li>요청자 본인이 올렸고 글 작성자도 본인이어야 한다 → 아니면 403 {@code attachment_forbidden}(B2)</li>
	 * <li>CONFIRMED 여야 한다 → 400 {@code attachment_not_confirmed}</li>
	 * <li>다른 글에 붙어 있지 않아야 한다 → 400 {@code attachment_in_use}</li>
	 * </ul>
	 * 없는 id 는 400 {@code unknown_attachment}. 목록에서 빠진 첨부는 연결을 푼다(객체는 지우지 않는다). 행을 잠그고 바꾸므로 같은
	 * 첨부를 두 글에 동시에 붙일 수 없다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void replaceLinks(Long postId, Long postOwnerId, AuthUser editor, List<Long> requestedIds) {
		Set<Long> ids = new LinkedHashSet<>(requestedIds);
		Map<Long, Attachment> requested = ids.isEmpty() ? Map.of()
				: attachments.findAllForUpdate(ids).stream().collect(Collectors.toMap(Attachment::getId, Function.identity()));
		for (Long id : ids) {
			Attachment attachment = requested.get(id);
			if (attachment == null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, UNKNOWN, "없는 첨부입니다: " + id);
			}
			if (postId.equals(attachment.getPostId())) {
				continue;
			}
			if (!attachment.getOwnerId().equals(editor.id()) || !attachment.getOwnerId().equals(postOwnerId)) {
				throw forbidden();
			}
			if (!attachment.isConfirmed()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, NOT_CONFIRMED, "업로드 확정(confirm) 전인 첨부입니다: " + id);
			}
			if (attachment.getPostId() != null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, IN_USE, "다른 글에 붙은 첨부입니다: " + id);
			}
		}
		for (Attachment current : attachments.findByPostIdOrderByIdAsc(postId)) {
			if (!ids.contains(current.getId())) {
				current.detach();
			}
		}
		requested.values().forEach(attachment -> attachment.attachTo(postId));
		attachments.flush();
	}

	/** 글 상세의 첨부 (올린 순). */
	@Transactional(readOnly = true)
	public List<AttachmentView> forPost(Long postId) {
		return attachments.findByPostIdOrderByIdAsc(postId).stream().map(a -> AttachmentView.of(a, properties)).toList();
	}

	private static boolean matches(Attachment attachment, StoredObject stored) {
		return stored.size() == attachment.getSize() && stored.size() <= ImageTypes.MAX_SIZE
				&& attachment.getContentType().equals(ImageTypes.normalize(stored.contentType()));
	}

	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (RuntimeException ex) {
			log.warn("첨부 객체 삭제 실패(lifecycle 이 정리한다): {}", key, ex);
		}
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "첨부를 찾을 수 없습니다");
	}

	static ApiException forbidden() {
		return new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN, "본인이 올린 첨부만 쓸 수 있습니다");
	}

}
