package com.example.board.sms;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * 휴대폰 번호를 E.164({@code +821012345678})로 정규화한다 (libphonenumber). {@code +} 없이 들어온 번호는 기본 지역(KR)으로
 * 해석한다. 숫자·공백·{@code + - ( ) .} 외의 문자가 있거나 유효한 번호가 아니면 400 {@code invalid_phone}.
 */
public final class PhoneNumbers {

	public static final String INVALID_PHONE = "invalid_phone";

	private static final Pattern ALLOWED = Pattern.compile("^[0-9+()\\-.\\s]{4,32}$");

	private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

	private final String defaultRegion;

	public PhoneNumbers(String defaultRegion) {
		this.defaultRegion = defaultRegion.toUpperCase(Locale.ROOT);
	}

	public String normalize(String raw) {
		if (raw == null || !ALLOWED.matcher(raw.strip()).matches()) {
			throw invalid();
		}
		try {
			PhoneNumber number = util.parse(raw.strip(), defaultRegion);
			if (!util.isValidNumber(number)) {
				throw invalid();
			}
			return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
		}
		catch (NumberParseException ex) {
			throw invalid();
		}
	}

	/** 로그용: 국가 코드 뒤를 가리고 끝 4자리만 남긴다. {@code +821012345678 → +82*****5678} */
	public static String mask(String e164) {
		if (e164 == null || e164.length() < 8) {
			return "****";
		}
		return e164.substring(0, 3) + "*".repeat(e164.length() - 7) + e164.substring(e164.length() - 4);
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, INVALID_PHONE, "휴대폰 번호가 올바르지 않습니다");
	}

}
