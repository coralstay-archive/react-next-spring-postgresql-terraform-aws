package com.example.board.sms;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.example.board.common.ApiException;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * 휴대폰 번호를 E.164({@code +821012345678})로 정규화한다 (libphonenumber). {@code +} 없이 들어온 번호는 기본 지역(KR)으로
 * 해석한다. 숫자·공백·{@code + - ( ) .} 외의 문자가 있거나 유효한 번호가 아니면 400 {@code invalid_phone}.
 *
 * <p>SMS 발송 대상({@link #sendable}, TASK-22#4, doc-2 BE-25): 허용 국가({@code app.sms.allowed-regions}, 기본 KR)의 휴대폰
 * (MOBILE·FIXED_LINE_OR_MOBILE)만. 그 밖(다른 나라, 유선·인터넷전화·대표번호·수신자부담 등)은 400 {@code phone_not_allowed} —
 * 고가 국제 번호로 SMS 비용을 빼 가는 pumping 을 막는다.
 */
public final class PhoneNumbers {

	public static final String INVALID_PHONE = "invalid_phone";

	public static final String PHONE_NOT_ALLOWED = "phone_not_allowed";

	private static final Set<PhoneNumberType> SENDABLE_TYPES = Set.of(PhoneNumberType.MOBILE,
			PhoneNumberType.FIXED_LINE_OR_MOBILE);

	private static final Pattern ALLOWED = Pattern.compile("^[0-9+()\\-.\\s]{4,32}$");

	private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

	private final String defaultRegion;

	private final Set<String> allowedRegions;

	public PhoneNumbers(String defaultRegion, Set<String> allowedRegions) {
		this.defaultRegion = defaultRegion.toUpperCase(Locale.ROOT);
		this.allowedRegions = Set.copyOf(allowedRegions);
	}

	public String normalize(String raw) {
		return util.format(parse(raw), PhoneNumberUtil.PhoneNumberFormat.E164);
	}

	/**
	 * SMS 를 보낼 수 있는 번호면 E.164. 형식이 틀리면 400 {@code invalid_phone}, 허용 국가의 휴대폰이 아니면 400
	 * {@code phone_not_allowed}. 발송 한도보다 먼저 부른다(거절된 번호는 한도를 소모하지 않는다, TASK-22 B3).
	 */
	public String sendable(String raw) {
		PhoneNumber number = parse(raw);
		String region = util.getRegionCodeForNumber(number);
		if (region == null || !allowedRegions.contains(region) || !SENDABLE_TYPES.contains(util.getNumberType(number))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, PHONE_NOT_ALLOWED, "인증번호를 보낼 수 없는 번호입니다. 휴대폰 번호("
					+ String.join("·", allowedRegions.stream().sorted().toList()) + ")만 쓸 수 있습니다");
		}
		return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
	}

	private PhoneNumber parse(String raw) {
		if (raw == null || !ALLOWED.matcher(raw.strip()).matches()) {
			throw invalid();
		}
		try {
			PhoneNumber number = util.parse(raw.strip(), defaultRegion);
			if (!util.isValidNumber(number)) {
				throw invalid();
			}
			return number;
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
