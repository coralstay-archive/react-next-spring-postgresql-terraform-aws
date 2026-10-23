package com.example.board.attachment;

import java.time.Clock;
import java.time.Instant;
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
import com.example.board.common.RateLimitProperties;
import com.example.board.common.RateLimiter;

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

	/** pending-ttl 이 지나 확정할 수 없다 (TASK-26#2). 410. 다시 presign 해서 올린다. */
	public static final String EXPIRED = "attachment_expired";

	/** 미확정(PENDING) 첨부가 한도에 이름 (TASK-22#3). 409. */
	public static final String TOO_MANY_PENDING = "too_many_pending_attachments";

	/** 레이트리밋 bucket (TASK-22#3). */
	static final String PRESIGN = "presign";

	/** 글 하나에 붙일 수 있는 첨부 수. */
	public static final int MAX_PER_POST = 10;

	private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

	private final AttachmentRepository attachments;

	private final UploadStorage storage;

	private final UploadProperties properties;

	private final Clock clock;

	private final RateLimiter rateLimiter;

	private final RateLimitProperties rateLimits;

	public AttachmentService(AttachmentRepository attachments, UploadStorage storage, UploadProperties properties,
			Clock clock, RateLimiter rateLimiter, RateLimitProperties rateLimits) {
		this.rateLimiter = rateLimiter;
		this.rateLimits = rateLimits;
		this.attachments = attachments;
		this.storage = storage;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * PUT URL 발급 (TASK-11#1). B1: 허용 타입(png·jpeg·webp·gif)·5MB 이하만. 키는 {@code uploads/{userId}/{uuid}.{ext}} — 확장자는
	 * 타입에서 정하고 파일명은 쓰지 않는다. 행은 PENDING 으로 남고 confirm 하지 않으면 정리 대상이 된다.
	 *
	 * <p>할당량 (TASK-22#3): 사용자별 시간당 presign 한도를 넘으면 429, 미확정(PENDING) 첨부가 한도에 이르렀으면 409
	 * {@value #TOO_MANY_PENDING}. 한도 기록과 개수 확인은 같은 사용자 잠금(이 트랜잭션) 안이라 동시 presign 으로 넘길 수 없고,
	 * 거절(롤백)된 presign 은 시간당 한도를 소모하지 않는다.
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
		rateLimiter.hit(new RateLimiter.Rule(PRESIGN, String.valueOf(user.id()), rateLimits.presign()));
		if (attachments.countByOwnerIdAndStatus(user.id(), AttachmentStatus.PENDING) >= rateLimits.maxPendingAttachments()) {
			throw new ApiException(HttpStatus.CONFLICT, TOO_MANY_PENDING, "업로드를 마치지 않은 이미지가 너무 많습니다. 올리던 이미지를"
					+ " 마무리하거나 잠시 후 다시 시도하세요");
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
	 * <p>S3 호출이 DB 트랜잭션을 잡고 있지 않도록 트랜잭션 없이 돌고, 마지막 상태 변경만 조건부 UPDATE 로 한다. S3 오류는 503
	 * {@code storage_unavailable}(TASK-26#1)이고 첨부는 PENDING 그대로다.
	 *
	 * <p>만료 (TASK-26#2, BE-33): 만든 지 pending-ttl 이 지난 첨부는 410 {@value #EXPIRED} — 정리 작업({@link PendingAttachmentCleanup})과
	 * S3 lifecycle 이 지울 대상이다. 태그를 지운 뒤 확정(markConfirmed)이 0행이면 다시 읽어서, 동시 confirm 이 먼저 확정했으면 그대로 200,
	 * 아니면(정리가 행을 지웠거나 그사이 만료) 태그 없는 객체가 영구 고아로 남지 않게 지우고 410. 정리 작업은 행을 먼저 지우고 객체를 지우므로
	 * 어느 쪽이 이겨도 CONFIRMED 행은 객체가 있다(B1).
	 */
	public AttachmentView confirm(Long id, AuthUser user) {
		Attachment attachment = attachments.findById(id).orElseThrow(AttachmentService::notFound);
		if (!attachment.getOwnerId().equals(user.id())) {
			throw forbidden();
		}
		if (attachment.isConfirmed()) {
			return AttachmentView.of(attachment, properties);
		}
		if (!attachment.getCreatedAt().isAfter(pendingCutoff())) {
			throw expired();
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
		if (attachments.markConfirmed(id, clock.instant(), pendingCutoff()) == 0) {
			Attachment current = attachments.findById(id).orElse(null);
			if (current != null && current.isConfirmed()) {
				return AttachmentView.of(current, properties);
			}
			deleteQuietly(key);
			throw expired();
		}
		return attachments.findById(id).map(a -> AttachmentView.of(a, properties))
			.orElseThrow(AttachmentService::notFound);
	}

	/** 이 시각 이전(포함)에 만든 PENDING 첨부는 만료다. */
	private Instant pendingCutoff() {
		return clock.instant().minus(properties.pendingTtl());
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
			// S3 오류 원인은 저장소가 이미 남겼다(TASK-26#1). 태그를 지운 뒤라면 lifecycle 도 지우지 않는 고아 객체다
			log.warn("첨부 객체 삭제 실패 key={} causes={}", key, ex.getClass().getSimpleName());
		}
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "첨부를 찾을 수 없습니다");
	}

	static ApiException expired() {
		return new ApiException(HttpStatus.GONE, EXPIRED, "업로드 확정 시간이 지났습니다. 이미지를 다시 올려 주세요");
	}

	static ApiException forbidden() {
		return new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN, "본인이 올린 첨부만 쓸 수 있습니다");
	}

}
