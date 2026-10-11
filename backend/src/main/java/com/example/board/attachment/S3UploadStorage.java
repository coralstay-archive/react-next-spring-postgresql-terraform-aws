package com.example.board.attachment;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** S3(또는 MinIO) 저장소 (TASK-11). */
final class S3UploadStorage implements UploadStorage {

	/** 응답 headers 에서 빼는 서명 헤더: HTTP 클라이언트가 직접 붙이고 브라우저는 JS 로 설정할 수도 없다. */
	private static final List<String> CLIENT_MANAGED = List.of("host", "content-length");

	private final S3Client s3;

	private final S3Presigner presigner;

	private final String bucket;

	S3UploadStorage(S3Client s3, S3Presigner presigner, String bucket) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = bucket;
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public PresignedUpload presignPut(String key, String contentType, long size, Duration ttl) {
		PutObjectRequest put = PutObjectRequest.builder()
			.bucket(bucket)
			.key(key)
			.contentType(contentType)
			.contentLength(size)
			.tagging(PENDING_TAG)
			.build();
		PresignedPutObjectRequest presigned = presigner
			.presignPutObject(request -> request.signatureDuration(ttl).putObjectRequest(put));
		Map<String, String> headers = new LinkedHashMap<>();
		presigned.signedHeaders().forEach((name, values) -> {
			if (!CLIENT_MANAGED.contains(name.toLowerCase(Locale.ROOT))) {
				headers.put(name, String.join(",", values));
			}
		});
		return new PresignedUpload(presigned.url().toString(), headers, presigned.expiration());
	}

	@Override
	public Optional<StoredObject> head(String key) {
		try {
			HeadObjectResponse head = s3.headObject(request -> request.bucket(bucket).key(key));
			return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
		}
		catch (NoSuchKeyException ex) {
			return Optional.empty();
		}
		catch (S3Exception ex) {
			if (ex.statusCode() == 404) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	@Override
	public void clearPendingTag(String key) {
		s3.deleteObjectTagging(request -> request.bucket(bucket).key(key));
	}

	@Override
	public void delete(String key) {
		s3.deleteObject(request -> request.bucket(bucket).key(key));
	}

}
