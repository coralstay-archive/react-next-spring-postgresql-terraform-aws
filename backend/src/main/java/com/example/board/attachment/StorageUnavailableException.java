package com.example.board.attachment;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/**
 * 첨부 저장소(S3) 호출 실패 (TASK-26#1, BE-32): 403(권한·자격 증명), 5xx, 연결·타임아웃 오류(SdkClientException). 503
 * {@value #CODE}. 원인은 {@link S3UploadStorage} 가 WARN 로그로 남기고 응답에는 싣지 않는다. 잠시 뒤 다시 시도하면 된다.
 */
public class StorageUnavailableException extends ApiException {

	private static final long serialVersionUID = 1L;

	public static final String CODE = "storage_unavailable";

	public StorageUnavailableException(Throwable cause) {
		super(HttpStatus.SERVICE_UNAVAILABLE, CODE, "이미지 저장소에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도하세요");
		initCause(cause);
	}

}
