package com.example.board.attachment;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** 버킷이 설정되지 않았을 때(TASK-11#1). 앱은 뜨고, 업로드 작업은 503 {@code uploads_unavailable}. */
final class DisabledUploadStorage implements UploadStorage {

	static final String UNAVAILABLE = "uploads_unavailable";

	@Override
	public boolean available() {
		return false;
	}

	@Override
	public PresignedUpload presignPut(String key, String contentType, long size, Duration ttl) {
		throw unavailable();
	}

	@Override
	public Optional<StoredObject> head(String key) {
		throw unavailable();
	}

	@Override
	public void clearPendingTag(String key) {
		throw unavailable();
	}

	@Override
	public void delete(String key) {
		throw unavailable();
	}

	static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, "첨부 업로드를 사용할 수 없습니다");
	}

}
