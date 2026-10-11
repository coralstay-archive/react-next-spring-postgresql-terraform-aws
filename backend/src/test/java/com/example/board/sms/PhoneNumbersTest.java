package com.example.board.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** TASK-7#1 휴대폰 번호 E.164 정규화 (libphonenumber, 기본 지역 KR). */
class PhoneNumbersTest {

	private final PhoneNumbers phones = new PhoneNumbers("KR");

	@ParameterizedTest
	@ValueSource(strings = { "010-1234-5678", "01012345678", "010 1234 5678", "+82 10-1234-5678", "+821012345678",
			"(010) 1234-5678" })
	@DisplayName("TASK-7#1 국내 표기와 국제 표기는 같은 E.164(+821012345678)가 된다")
	void normalizesKoreanNumbers(String raw) {
		assertThat(phones.normalize(raw)).isEqualTo("+821012345678");
	}

	@Test
	@DisplayName("TASK-7#1 + 로 시작하면 다른 나라 번호도 E.164 로 정규화한다")
	void normalizesInternationalNumbers() {
		assertThat(phones.normalize("+1 650-253-0000")).isEqualTo("+16502530000");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "abc", "010-12", "12345678901234567890", "+82 10-1234-56789", "010-1234-5678x" })
	@DisplayName("TASK-7#1 형식이 틀리거나 유효하지 않은 번호는 400 invalid_phone")
	void rejectsInvalid(String raw) {
		assertThatThrownBy(() -> phones.normalize(raw)).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(ex.getError()).isEqualTo(PhoneNumbers.INVALID_PHONE);
		});
	}

	@Test
	@DisplayName("TASK-7#1 로그·표시용 마스킹은 끝 4자리만 남긴다")
	void masks() {
		assertThat(PhoneNumbers.mask("+821012345678")).isEqualTo("+82******5678");
	}

}
