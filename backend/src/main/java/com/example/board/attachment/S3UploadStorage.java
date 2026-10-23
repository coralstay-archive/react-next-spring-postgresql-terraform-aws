package com.example.board.attachment;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * S3(또는 MinIO) 저장소 (TASK-11).
 *
 * <p>S3 오류(TASK-26#1, BE-32): SDK 예외(403·5xx 같은 서비스 오류, 연결·타임아웃 같은 클라이언트 오류)는 503
 * {@link StorageUnavailableException} 으로 바꾸고 WARN 로그 한 줄을 남긴다 — 작업·키·HTTP 상태·AWS 오류 코드·request id·예외 클래스 사슬.
 * 예외 메시지·presigned URL·자격 증명은 남기지 않는다(TASK-25 로그 방식). 키({@code uploads/{userId}/{uuid}.{ext}})는 사용자 id 외의 개인정보가
 * 없어 남긴다.
 */
final class S3UploadStorage implements UploadStorage {

	private static final Logger log = LoggerFactory.getLogger(S3UploadStorage.class);

	private static final int MAX_CAUSES = 8;

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
		PresignedPutObjectRequest presigned = call("presign", key,
				() -> presigner.presignPutObject(request -> request.signatureDuration(ttl).putObjectRequest(put)));
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
		return call("head", key, () -> {
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
		});
	}

	@Override
	public void clearPendingTag(String key) {
		call("clear-pending-tag", key, () -> s3.deleteObjectTagging(request -> request.bucket(bucket).key(key)));
	}

	@Override
	public void delete(String key) {
		call("delete", key, () -> s3.deleteObject(request -> request.bucket(bucket).key(key)));
	}

	private static <T> T call(String operation, String key, Supplier<T> action) {
		try {
			return action.get();
		}
		catch (SdkException ex) {
			log.warn("첨부 저장소 오류 op={} key={}{} causes={}", operation, key, service(ex), causes(ex));
			throw new StorageUnavailableException(ex);
		}
	}

	/** 서비스 오류면 {@code " status=403 code=AccessDenied requestId=…"}, 아니면 빈 문자열. 메시지 원문은 보지 않는다. */
	static String service(SdkException exception) {
		if (!(exception instanceof AwsServiceException service)) {
			return "";
		}
		StringBuilder details = new StringBuilder(" status=").append(service.statusCode());
		if (service.awsErrorDetails() != null && service.awsErrorDetails().errorCode() != null) {
			details.append(" code=").append(service.awsErrorDetails().errorCode());
		}
		if (service.requestId() != null) {
			details.append(" requestId=").append(service.requestId());
		}
		return details.toString();
	}

	/** 예외 클래스 이름 사슬(예: {@code SdkClientException <- ConnectException}). */
	static String causes(Throwable exception) {
		StringJoiner chain = new StringJoiner(" <- ");
		int depth = 0;
		for (Throwable t = exception; t != null && depth < MAX_CAUSES; t = t.getCause() == t ? null : t.getCause(), depth++) {
			chain.add(t.getClass().getSimpleName());
		}
		return chain.toString();
	}

}
