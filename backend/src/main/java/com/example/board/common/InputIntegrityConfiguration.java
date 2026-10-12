package com.example.board.common;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;

/**
 * 사용자 입력 문자열의 제어 문자 거부 (TASK-20#2, B2). 저장·검색까지 가서 DB 오류(500)가 나기 전에 400 {@code validation} 으로 막는다.
 * <ul>
 * <li>JSON 본문: 모든 문자열 값(리스트 원소 포함)을 읽을 때 검사한다.</li>
 * <li>쿼리 파라미터: 컨트롤러에 들어가기 전에 모든 값을 검사한다.</li>
 * </ul>
 *
 * <p>본문 엄격함(TASK-20#3: 알 수 없는 필드 거부 + 제어 문자 거부)은 <b>Spring MVC 요청 본문에만</b> 건다. Boot 의 전역 JsonMapper
 * 빈은 RestClient·RestTemplate 빌더(외부 응답 파싱) 등도 쓰므로 너그러운 그대로 두고, 그 복사본에 엄격 설정을 더한 JSON 변환기를 서버
 * 쪽 변환기 목록에만 넣는다.
 */
@Configuration(proxyBeanMethods = false)
public class InputIntegrityConfiguration implements WebMvcConfigurer {

	/**
	 * 서버(MVC) 쪽 JSON 변환기만 엄격한 매퍼로 바꾼다. Boot 의 기본 customizer(@Order(0), 전역 매퍼) 다음에 적용돼야 하므로 가장 낮은
	 * 우선순위로 둔다. 클라이언트 쪽(ClientHttpMessageConvertersCustomizer)은 건드리지 않는다.
	 */
	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	ServerHttpMessageConvertersCustomizer strictRequestBodyConverter(JsonMapper jsonMapper) {
		JsonMapper strict = strictCopy(jsonMapper);
		return builder -> builder.withJsonConverter(new JacksonJsonHttpMessageConverter(strict));
	}

	/** 전역 매퍼 설정(Boot 의 spring.jackson.* 등)을 그대로 두고 요청 본문용 엄격함만 더한 복사본. */
	static JsonMapper strictCopy(JsonMapper jsonMapper) {
		return jsonMapper.rebuild()
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.addModule(new SimpleModule("control-characters").addDeserializer(String.class,
					new RejectingStringDeserializer()))
			.build();
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
