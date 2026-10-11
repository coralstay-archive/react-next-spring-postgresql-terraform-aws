package com.example.board.attachment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 첨부 요청 (TASK-11). */
public final class AttachmentRequests {

	private AttachmentRequests() {
	}

	/**
	 * presign 요청. 타입·크기 허용 여부(B1)는 서비스가 고유 오류 코드로 판단한다. {@code filename} 은 선택이고 키에 쓰지 않는다.
	 */
	public record Presign(@NotNull(message = "contentType 을 입력하세요") String contentType,
			@NotNull(message = "size 를 입력하세요") @Positive(message = "size 는 1 이상이어야 합니다") Long size,
			@Size(max = 255, message = "filename 은 255자 이하입니다") String filename) {
	}

}
