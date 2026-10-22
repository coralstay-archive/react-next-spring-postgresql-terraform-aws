package com.example.board.security;

import java.util.Locale;

import org.springframework.core.env.Environment;

/**
 * 운영 모드 판정 (TASK-21). {@code app.security.production-mode}(env {@code PRODUCTION_MODE})가 {@code auto}(기본)면
 * {@code app.public-base-url}(env {@code PUBLIC_BASE_URL})이 {@code https://} 로 시작할 때 운영이다. 로컬·테스트·E2E 는 http 라서
 * 운영이 아니다. {@code true}·{@code false} 로 판정을 강제할 수 있다.
 */
public final class ProductionMode {

	public static final String PROPERTY = "app.security.production-mode";

	private ProductionMode() {
	}

	public static boolean isProduction(Environment env) {
		String mode = env.getProperty(PROPERTY, "auto").strip().toLowerCase(Locale.ROOT);
		return switch (mode) {
			case "true" -> true;
			case "false" -> false;
			case "auto", "" -> env.getProperty("app.public-base-url", "").strip().toLowerCase(Locale.ROOT)
				.startsWith("https://");
			default -> throw new IllegalArgumentException(
					PROPERTY + " 는 auto|true|false 중 하나여야 한다 (env PRODUCTION_MODE): " + mode);
		};
	}

	/** 판정 근거 (로그·오류 메시지용). 비밀값이 아니다. */
	static String describe(Environment env) {
		return PROPERTY + "=" + env.getProperty(PROPERTY, "auto") + ", app.public-base-url="
				+ env.getProperty("app.public-base-url", "");
	}

}
