package com.example.board.attachment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 첨부 업로드 설정 {@code app.uploads.*} (TASK-11).
 *
 * @param bucket 버킷. 비우면 업로드 기능을 끈다(S3 클라이언트를 만들지 않고 presign·confirm 은 503 {@code uploads_unavailable})
 * @param region 리전
 * @param endpoint S3 호환 엔드포인트(로컬 MinIO 등). 비우면 AWS 기본
 * @param pathStyle path-style 주소(MinIO 는 true)
 * @param accessKey 정적 자격 증명(로컬 MinIO 전용). 비우면 SDK 기본 체인(EC2 인스턴스 프로파일)
 * @param secretKey 정적 자격 증명 secret
 * @param publicBasePath 첨부를 보여 줄 주소의 앞부분. 공개 URL = 이 값 + 키에서 {@code uploads/} 뒤. 운영은 CloudFront
 * {@code /uploads/*} 라 기본 {@code /uploads/}(같은 오리진 상대 경로). 로컬 MinIO 는
 * {@code http://localhost:9000/<bucket>/uploads/}
 * @param presignTtl presigned PUT URL 유효 시간
 * @param pendingTtl 이보다 오래된 PENDING 첨부는 정리한다(S3 lifecycle 1일과 맞춘다). 지난 첨부의 confirm 은 410
 * {@code attachment_expired}(TASK-26#2)
 * @param apiCallTimeout S3 호출 하나(재시도 포함)의 상한 (기본 5초, TASK-26#3 BE-35). 넘으면 503 {@code storage_unavailable}
 * @param apiCallAttemptTimeout HTTP 시도 하나의 상한 (기본 2초). 연결·소켓 타임아웃도 이 값이다. 전체보다 길 수 없다
 * @param orphanGracePeriod 글에서 뗀(또는 붙이지 않은) 확정 첨부와 삭제된 글의 첨부를 지우기 전 유예 기간 (기본 7일, TASK-26#4 BE-36). 이
 * 안에 다시 붙이거나 글을 복구하면 이미지가 그대로다. 양수여야 한다
 */
@ConfigurationProperties("app.uploads")
public record UploadProperties(@DefaultValue("") String bucket, @DefaultValue("us-east-1") String region,
		@DefaultValue("") String endpoint, @DefaultValue("false") boolean pathStyle, @DefaultValue("") String accessKey,
		@DefaultValue("") String secretKey, @DefaultValue("/uploads/") String publicBasePath,
		@DefaultValue("PT5M") Duration presignTtl, @DefaultValue("PT24H") Duration pendingTtl,
		@DefaultValue("5s") Duration apiCallTimeout, @DefaultValue("2s") Duration apiCallAttemptTimeout,
		@DefaultValue("P7D") Duration orphanGracePeriod) {

	public UploadProperties {
		positive(apiCallTimeout, "api-call-timeout");
		positive(apiCallAttemptTimeout, "api-call-attempt-timeout");
		positive(orphanGracePeriod, "orphan-grace-period");
		if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
			throw new IllegalArgumentException("app.uploads.api-call-attempt-timeout(" + apiCallAttemptTimeout
					+ ") 은 api-call-timeout(" + apiCallTimeout + ") 보다 길 수 없다");
		}
	}

	private static void positive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("app.uploads." + name + " 는 양수여야 한다: " + value);
		}
	}

	/** 객체 키 접두어. CloudFront {@code /uploads/*} 경로와 S3 lifecycle 필터가 이 접두어를 쓴다. */
	public static final String KEY_PREFIX = "uploads/";

	public boolean enabled() {
		return bucket != null && !bucket.isBlank();
	}

	/** 공개 URL: base(끝 / 보정) + 키에서 {@value #KEY_PREFIX} 뒤. */
	public static String publicUrl(String basePath, String key) {
		String base = basePath.endsWith("/") ? basePath : basePath + "/";
		String rest = key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : key;
		return base + rest;
	}

	public String publicUrl(String key) {
		return publicUrl(publicBasePath, key);
	}

}
