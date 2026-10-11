package com.example.board.auth.local;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 비밀번호 정책: 8자 이상, UTF-8 72바이트 이하, 문자와 숫자를 각각 하나 이상 포함.
 *
 * <p>상한이 "72바이트"인 이유: BCrypt 는 입력의 앞 72바이트만 쓴다(Spring Security 는 넘으면 예외). 글자 수로 제한하면
 * 한글처럼 멀티바이트 문자가 섞였을 때 뒷부분이 조용히 무시되거나 500 이 나므로 바이트로 잰다.
 */
@Documented
@Constraint(validatedBy = PasswordPolicy.Validator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

	int MIN_LENGTH = 8;

	int MAX_BYTES = 72;

	String message() default "비밀번호는 8자 이상 72바이트 이하이고 문자와 숫자를 모두 포함해야 합니다";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<PasswordPolicy, String> {

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			if (value == null) {
				return false;
			}
			return value.codePointCount(0, value.length()) >= MIN_LENGTH
					&& value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES
					&& value.codePoints().anyMatch(Character::isLetter)
					&& value.codePoints().anyMatch(Character::isDigit);
		}

	}

}
