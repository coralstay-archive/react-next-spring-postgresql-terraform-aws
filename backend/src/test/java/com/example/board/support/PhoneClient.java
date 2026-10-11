package com.example.board.support;

import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.board.sms.InMemorySmsSender;
import com.example.board.sms.SmsSender;

/**
 * TASK-7 휴대폰 OTP API 를 실제 HTTP 로 부르는 테스트 헬퍼. 인증번호는 메모리 SMS 수신함에서 꺼낸다.
 *
 * <p>클라이언트 IP 는 테스트마다 다른 값을 신뢰 헤더({@code CloudFront-Viewer-Address})로 보낸다(TASK-7#4). 모든 요청이
 * 127.0.0.1 에서 오므로, 그러지 않으면 IP 별 발송 한도를 테스트끼리 나눠 쓰게 된다.
 */
public final class PhoneClient {

	public static final String IP_HEADER = "CloudFront-Viewer-Address";

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	private static final AtomicInteger SEQUENCE = new AtomicInteger(ThreadLocalRandom.current().nextInt(1_000_000));

	private final Api api;

	private final AuthClient auth;

	private final InMemorySmsSender inbox;

	private String ip = uniqueIp();

	public PhoneClient(Api api, SmsSender sender) {
		this.api = api;
		this.auth = new AuthClient(api);
		this.inbox = (InMemorySmsSender) sender;
	}

	/** 겹치지 않는 국내 휴대폰 번호(국내 표기). E.164 는 {@link #e164(String)}. */
	public static String uniquePhone() {
		// 010-5xxx 대역은 libphonenumber 규칙상 일부가 무효라 3 으로 시작하게 만든다.
		return String.format("010-3%03d-%04d", ThreadLocalRandom.current().nextInt(1000),
				SEQUENCE.incrementAndGet() % 10000);
	}

	public static String e164(String domestic) {
		return "+82" + domestic.replace("-", "").substring(1);
	}

	public static String uniqueIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	/** 이후 요청의 클라이언트 IP. */
	public PhoneClient ip(String ip) {
		this.ip = ip;
		return this;
	}

	public String ip() {
		return ip;
	}

	public Api.Request withIp(Api.Request request) {
		return request.header(IP_HEADER, ip + ":" + ThreadLocalRandom.current().nextInt(1024, 65535));
	}

	public static String otpBody(String phone) {
		return "{\"phone\":" + AuthClient.quote(phone) + "}";
	}

	public static String verifyBody(String challengeId, String code) {
		return "{\"challengeId\":" + AuthClient.quote(challengeId) + ",\"code\":" + AuthClient.quote(code) + "}";
	}

	/** {@code POST /auth/phone/otp} (CSRF 포함). */
	public HttpResponse<String> sendOtp(String phone) {
		return withIp(api.post("/auth/phone/otp")).csrf(auth.csrfToken()).json(otpBody(phone)).send();
	}

	/** 발송하고 challengeId 를 돌려준다. 202 가 아니면 예외. */
	public String challenge(String phone) {
		HttpResponse<String> response = sendOtp(phone);
		if (response.statusCode() != 202) {
			throw new IllegalStateException("otp failed: " + response.statusCode() + " " + response.body());
		}
		return AuthClient.json(response).get("challengeId").asString();
	}

	/** 그 번호(국내 표기 또는 E.164)로 마지막에 온 인증번호. */
	public String lastCode(String phone) {
		String to = phone.startsWith("+") ? phone : e164(phone);
		String message = inbox.lastMessageTo(to).orElseThrow(() -> new IllegalStateException("no sms to " + to));
		Matcher matcher = CODE.matcher(message);
		if (!matcher.find()) {
			throw new IllegalStateException("no code in " + message);
		}
		return matcher.group(1);
	}

	public int messageCount(String phone) {
		return inbox.messagesTo(phone.startsWith("+") ? phone : e164(phone)).size();
	}

	/** 6자리 중 정답이 아닌 값. */
	public static String wrong(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	/** {@code POST /auth/phone/verify{query}} (세션 경로면 CSRF 포함). session 은 {@code SESSION=...} 또는 null. */
	public HttpResponse<String> verify(String query, String challengeId, String code, String session) {
		Api.Request request = withIp(api.post("/auth/phone/verify" + query)).csrf(auth.csrfToken())
			.json(verifyBody(challengeId, code));
		if (session != null) {
			request.cookie(session);
		}
		return request.send();
	}

	public HttpResponse<String> verify(String challengeId, String code) {
		return verify("", challengeId, code, null);
	}

}
