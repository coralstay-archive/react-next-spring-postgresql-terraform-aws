package com.example.board.tag;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/**
 * 태그 이름 정규화 (TASK-8 B3): 유니코드 NFC → 앞뒤 공백 제거 → 안쪽 연속 공백을 한 칸으로 → 소문자(Locale.ROOT).
 * 결과가 비었거나 {@value #MAX_LENGTH}자(code point)를 넘으면 400 {@code invalid_tag}.
 */
public final class TagNames {

	public static final int MAX_LENGTH = 30;

	public static final String INVALID = "invalid_tag";

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private TagNames() {
	}

	public static String normalize(String raw) {
		if (raw == null) {
			throw invalid("태그가 비어 있습니다");
		}
		String name = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()).replaceAll(" ")
			.toLowerCase(Locale.ROOT);
		if (name.isEmpty()) {
			throw invalid("태그가 비어 있습니다");
		}
		if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
			throw invalid("태그는 " + MAX_LENGTH + "자 이하여야 합니다");
		}
		return name;
	}

	private static ApiException invalid(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, INVALID, message);
	}

}
