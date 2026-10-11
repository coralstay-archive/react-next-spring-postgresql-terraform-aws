package com.example.board.attachment;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 첨부 객체 저장소 (TASK-11). 운영은 S3, 로컬·테스트는 MinIO. {@link UploadConfiguration} 이 고른다. */
public interface UploadStorage {

	/** presign 때 객체에 강제하는 태그. S3 lifecycle 이 이 태그가 남은 객체를 1일 뒤 지운다. confirm 때 지운다. */
	String PENDING_TAG = "status=pending";

	/** 업로드가 켜져 있는지(버킷 설정). */
	boolean available();

	/**
	 * PUT URL 을 발급한다. Content-Type·Content-Length·{@code x-amz-tagging: status=pending} 을 서명에 넣어, 클라이언트가 다른 값을
	 * 보내면 저장소가 서명 불일치로 거부한다.
	 */
	PresignedUpload presignPut(String key, String contentType, long size, Duration ttl);

	/** HeadObject. 없으면 empty. */
	Optional<StoredObject> head(String key);

	/** 미확정 태그(status=pending)를 지운다(DeleteObjectTagging) → lifecycle 대상에서 빠진다. */
	void clearPendingTag(String key);

	/** 객체를 지운다(없어도 성공). */
	void delete(String key);

	/**
	 * @param url PUT 할 URL
	 * @param headers 클라이언트가 그대로 보내야 하는 헤더(Host·Content-Length 는 HTTP 클라이언트가 붙이므로 뺐다)
	 * @param expiresAt URL 만료 시각
	 */
	record PresignedUpload(String url, Map<String, String> headers, Instant expiresAt) {
	}

	record StoredObject(long size, String contentType) {
	}

}
