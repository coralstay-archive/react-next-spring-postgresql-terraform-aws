package com.example.board.attachment;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 허용 이미지 타입 (TASK-11 B1): image/png·jpeg·webp·gif 만, 5MB 이하. 확장자는 타입에서 정한다(파일명은 믿지 않는다). */
public final class ImageTypes {

	/** 5MB. */
	public static final long MAX_SIZE = 5L * 1024 * 1024;

	private static final Map<String, String> EXTENSIONS = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp",
			"webp", "image/gif", "gif");

	private ImageTypes() {
	}

	/** 앞뒤 공백을 자르고 소문자로. 파라미터(;charset=…)가 붙은 값은 정규화하지 않는다(허용 목록에 없으니 거부된다). */
	public static String normalize(String contentType) {
		return contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
	}

	/** 허용 타입이면 확장자. */
	public static Optional<String> extension(String normalizedContentType) {
		return Optional.ofNullable(EXTENSIONS.get(normalizedContentType));
	}

	public static boolean isAllowed(String normalizedContentType) {
		return EXTENSIONS.containsKey(normalizedContentType);
	}

}
