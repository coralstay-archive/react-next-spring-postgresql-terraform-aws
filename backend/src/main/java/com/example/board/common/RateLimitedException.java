package com.example.board.common;

import org.springframework.http.HttpStatus;

/** 요청 한도 초과. 429 {@code rate_limited} + {@code Retry-After}(초) 헤더로 응답한다. */
public class RateLimitedException extends ApiException {

	private static final long serialVersionUID = 1L;

	public static final String CODE = "rate_limited";

	private final long retryAfterSeconds;

	public RateLimitedException(long retryAfterSeconds, String message) {
		super(HttpStatus.TOO_MANY_REQUESTS, CODE, message);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

}
