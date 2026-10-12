package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;

/** TASK-20#4 enum 파라미터 변환: 정확히 같으면 그 값, 아니면 대소문자 무시 비교(상수 이름이 대문자가 아니어도). */
class CaseInsensitiveEnumConverterTest {

	enum Mixed {

		alpha, Beta, GAMMA

	}

	@SuppressWarnings("unchecked")
	private static Converter<String, Mixed> converter() {
		return (Converter<String, Mixed>) (Converter<?, ?>) new ParameterConfiguration.CaseInsensitiveEnumConverterFactory()
			.getConverter(Mixed.class);
	}

	@Test
	@DisplayName("TASK-20#4 정확히 같은 이름, 대소문자만 다른 이름, 앞뒤 공백 모두 변환")
	void converts() {
		assertThat(converter().convert("alpha")).isEqualTo(Mixed.alpha);
		assertThat(converter().convert("ALPHA")).isEqualTo(Mixed.alpha);
		assertThat(converter().convert("beta")).isEqualTo(Mixed.Beta);
		assertThat(converter().convert(" gamma ")).isEqualTo(Mixed.GAMMA);
		assertThat(converter().convert("  ")).isNull();
	}

	@Test
	@DisplayName("TASK-20#4 없는 값은 IllegalArgumentException(→ 400 validation)")
	void unknown() {
		assertThatThrownBy(() -> converter().convert("delta")).isInstanceOf(IllegalArgumentException.class);
	}

}
