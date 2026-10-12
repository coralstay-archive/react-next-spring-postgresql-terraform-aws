package com.example.board.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 쿼리·경로 파라미터 변환 (TASK-20#4, BE-6). enum 파라미터(예: {@code ?status=suspended})는 앞뒤 공백을 자르고 대소문자를 가리지
 * 않는다. 없는 값은 변환 실패 → {@link ApiExceptionHandler} 가 400 {@code validation} + {@code fields} 로 바꾼다.
 */
@Configuration(proxyBeanMethods = false)
public class ParameterConfiguration implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverterFactory(new CaseInsensitiveEnumConverterFactory());
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	static final class CaseInsensitiveEnumConverterFactory implements ConverterFactory<String, Enum> {

		@Override
		public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
			Class<?> enumType = targetType;
			while (enumType != null && !enumType.isEnum()) {
				enumType = enumType.getSuperclass();
			}
			Class<T> type = (Class<T>) enumType;
			return source -> {
				String value = source.strip();
				if (value.isEmpty()) {
					return null;
				}
				for (T constant : type.getEnumConstants()) {
					if (constant.name().equals(value)) {
						return constant;
					}
				}
				for (T constant : type.getEnumConstants()) {
					if (constant.name().equalsIgnoreCase(value)) {
						return constant;
					}
				}
				throw new IllegalArgumentException("없는 값입니다: " + value);
			};
		}

	}

}
