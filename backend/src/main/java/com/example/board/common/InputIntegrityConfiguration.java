package com.example.board.common;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;

/**
 * 사용자 입력 문자열의 제어 문자 거부 (TASK-20#2, B2). 저장·검색까지 가서 DB 오류(500)가 나기 전에 400 {@code validation} 으로 막는다.
 * <ul>
 * <li>JSON 본문: 모든 문자열 값(리스트 원소 포함)을 읽을 때 검사한다(Boot 가 {@link JacksonModule} 빈을 JsonMapper 에 붙인다).</li>
 * <li>쿼리 파라미터: 컨트롤러에 들어가기 전에 모든 값을 검사한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class InputIntegrityConfiguration implements WebMvcConfigurer {

	@Bean
	JacksonModule controlCharacterModule() {
		return new SimpleModule("control-characters").addDeserializer(String.class, new RejectingStringDeserializer());
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ParameterInterceptor()).excludePathPatterns("/error");
	}

	/** 본문 문자열의 제어 문자. {@link ApiExceptionHandler} 가 400 validation + 필드 경로로 바꾼다. */
	public static final class ForbiddenCharacterException extends InvalidFormatException {

		private static final long serialVersionUID = 1L;

		ForbiddenCharacterException(JsonParser parser, String value) {
			super(parser, ControlCharacters.MESSAGE, value, String.class);
		}

	}

	static final class RejectingStringDeserializer extends StringDeserializer {

		@Override
		public String deserialize(JsonParser p, DeserializationContext ctxt) {
			String value = super.deserialize(p, ctxt);
			if (value != null && ControlCharacters.containsForbidden(value)) {
				throw new ForbiddenCharacterException(p, value);
			}
			return value;
		}

	}

	static final class ParameterInterceptor implements HandlerInterceptor {

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			request.getParameterMap().forEach((name, values) -> {
				for (String value : values) {
					if (ControlCharacters.containsForbidden(value)) {
						throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.VALIDATION,
								name + " 에 " + ControlCharacters.MESSAGE, Map.of(name, ControlCharacters.MESSAGE));
					}
				}
			});
			return true;
		}

	}

}
