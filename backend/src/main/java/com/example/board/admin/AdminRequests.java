package com.example.board.admin;

import jakarta.validation.constraints.Size;

/** 관리 API 요청 본문. */
public final class AdminRequests {

	private AdminRequests() {
	}

	/** 정지·숨김 등에 붙이는 선택 사유(감사 로그에 남는다). 본문 자체도 생략할 수 있다. */
	public record ReasonRequest(@Size(max = 500, message = "사유는 500자 이하여야 합니다") String reason) {

		static String of(ReasonRequest request) {
			return request == null ? null : request.reason();
		}

	}

}
