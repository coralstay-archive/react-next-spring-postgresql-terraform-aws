package com.example.board.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.board.common.ApiException;

/** TASK-8#B3 태그 이름 정규화(소문자, trim, 안쪽 공백 한 칸, 최대 30자, 빈 값 거부). */
class TagNamesTest {

	@ParameterizedTest(name = "TASK-8#B3 [{0}] → [{1}]")
	@CsvSource(delimiter = '|', value = { "Java|java", "'  Spring   Boot  '|spring boot", "'a\tb\nc'|a b c",
			"한글 태그|한글 태그", "C++|c++" })
	@DisplayName("TASK-8#B3 정규화: trim · 소문자 · 안쪽 공백 한 칸")
	void normalizes(String raw, String expected) {
		assertThat(TagNames.normalize(raw)).isEqualTo(expected);
	}

	@Test
	@DisplayName("TASK-8#B3 30자(code point)까지 허용, 31자는 거부")
	void maxLength() {
		assertThat(TagNames.normalize("가".repeat(30))).hasSize(30);
		assertThat(TagNames.normalize("  " + "A".repeat(30) + "  ")).isEqualTo("a".repeat(30));
		assertThatThrownBy(() -> TagNames.normalize("a".repeat(31))).isInstanceOf(ApiException.class)
			.extracting("error").isEqualTo("invalid_tag");
	}

	@ParameterizedTest(name = "TASK-8#B3 빈 태그 [{0}] 거부")
	@NullSource
	@ValueSource(strings = { "", "   ", "\t\n" })
	@DisplayName("TASK-8#B3 비었거나 공백뿐인 태그는 400 invalid_tag")
	void rejectsEmpty(String raw) {
		assertThatThrownBy(() -> TagNames.normalize(raw)).isInstanceOf(ApiException.class)
			.extracting("error").isEqualTo("invalid_tag");
	}

}
