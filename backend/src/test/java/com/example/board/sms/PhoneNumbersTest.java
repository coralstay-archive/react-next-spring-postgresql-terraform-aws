package com.example.board.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;

/** TASK-7#1 휴대폰 번호 E.164 정규화 (libphonenumber, 기본 지역 KR). */
class PhoneNumbersTest {

	private final PhoneNumbers phones = new PhoneNumbers("KR", Set.of("KR"));

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

	@ParameterizedTest
	@ValueSource(strings = { "010-1234-5678", "+82 10-1234-5678", "01012345678" })
	@DisplayName("TASK-22#4 발송 대상: 허용 국가(기본 KR)의 휴대폰 번호는 E.164 로 통과한다")
	void sendableAllowsKoreanMobile(String raw) {
		assertThat(phones.sendable(raw)).isEqualTo("+821012345678");
	}

	@ParameterizedTest
	@ValueSource(strings = { "+1 650-253-0000", "+44 7400 123456", "+81 90-1234-5678" })
	@DisplayName("TASK-22#B3 허용 국가가 아닌 번호는 400 phone_not_allowed")
	void sendableRejectsOtherRegions(String raw) {
		assertNotAllowed(raw);
	}

	@ParameterizedTest
	@ValueSource(strings = { "02-312-3456", "031-123-4567", "070-1234-5678", "1588-1234", "080-123-4567" })
	@DisplayName("TASK-22#4 허용 국가여도 휴대폰(MOBILE·FIXED_LINE_OR_MOBILE)이 아닌 번호(유선·인터넷전화·대표번호·수신자부담)는 400 phone_not_allowed")
	void sendableRejectsNonMobile(String raw) {
		assertNotAllowed(raw);
	}

	@Test
	@DisplayName("TASK-22#4 허용 국가 목록을 넓히면 그 나라 휴대폰(FIXED_LINE_OR_MOBILE 포함)도 보낼 수 있다")
	void sendableWithMoreRegions() {
		PhoneNumbers wider = new PhoneNumbers("KR", Set.of("KR", "US", "GB"));
		assertThat(wider.sendable("+1 650-253-0000")).isEqualTo("+16502530000");
		assertThat(wider.sendable("+44 7400 123456")).isEqualTo("+447400123456");
		assertThatThrownBy(() -> wider.sendable("+81 90-1234-5678")).isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("TASK-22#4 형식이 틀린 번호는 여전히 400 invalid_phone(허용 여부보다 먼저)")
	void sendableInvalidIsStillInvalid() {
		assertThatThrownBy(() -> phones.sendable("abc")).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getError()).isEqualTo(PhoneNumbers.INVALID_PHONE));
	}

	private void assertNotAllowed(String raw) {
		assertThatThrownBy(() -> phones.sendable(raw)).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(ex.getError()).isEqualTo(PhoneNumbers.PHONE_NOT_ALLOWED);
		});
	}

	@Test
	@DisplayName("TASK-22#4 app.sms.allowed-regions: 기본 KR, 대문자로 정규화, 모르는 지역 코드면 기동하지 않는다")
	void allowedRegionsProperty() {
		assertThat(new SmsProperties(null, null, null, null).allowedRegions()).containsExactly("KR");
		assertThat(new SmsProperties(null, null, Set.of(" kr ", "us"), null).allowedRegions())
			.containsExactlyInAnyOrder("KR", "US");
		assertThatThrownBy(() -> new SmsProperties(null, null, Set.of("XX"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("allowed-regions");
	}

	@Test
	@DisplayName("TASK-7#1 로그·표시용 마스킹은 끝 4자리만 남긴다")
	void masks() {
		assertThat(PhoneNumbers.mask("+821012345678")).isEqualTo("+82******5678");
	}

}
