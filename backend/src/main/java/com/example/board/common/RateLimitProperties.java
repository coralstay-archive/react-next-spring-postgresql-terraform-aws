package com.example.board.common;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공용 레이트리밋 설정 ({@code app.rate-limit.*}, TASK-22). 한도마다 {@code max}(창 안 최대 횟수)와 {@code window}(슬라이딩 창)를
 * 정한다. 빠진 값은 아래 기본값을 쓴다.
 *
 * @param loginAccount 로그인 실패 — 계정(정규화 이메일)별. 기본 15분 5회 (TASK-22#1)
 * @param loginIp 로그인 실패 — 클라이언트 IP 별. 기본 1시간 30회
 * @param signupIp 가입 시도 — 클라이언트 IP 별. 기본 1시간 10회
 * @param stepUp step-up 비밀번호 실패 — 사용자별. 기본 15분 5회 (B1)
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(Policy loginAccount, Policy loginIp, Policy signupIp, Policy stepUp) {

	public RateLimitProperties {
		loginAccount = Policy.orDefault(loginAccount, 5, Duration.ofMinutes(15));
		loginIp = Policy.orDefault(loginIp, 30, Duration.ofHours(1));
		signupIp = Policy.orDefault(signupIp, 10, Duration.ofHours(1));
		stepUp = Policy.orDefault(stepUp, 5, Duration.ofMinutes(15));
	}

	/**
	 * @param max 창 안에 허용하는 횟수 (1 이상)
	 * @param window 슬라이딩 창 길이 (양수)
	 */
	public record Policy(Integer max, Duration window) {

		public Policy {
			if (max != null && max < 1) {
				throw new IllegalArgumentException("app.rate-limit.*.max 는 1 이상이어야 한다: " + max);
			}
			if (window != null && (window.isZero() || window.isNegative())) {
				throw new IllegalArgumentException("app.rate-limit.*.window 는 양수여야 한다: " + window);
			}
		}

		static Policy orDefault(Policy policy, int max, Duration window) {
			if (policy == null) {
				return new Policy(max, window);
			}
			return new Policy(policy.max() == null ? max : policy.max(),
					policy.window() == null ? window : policy.window());
		}

	}

}
