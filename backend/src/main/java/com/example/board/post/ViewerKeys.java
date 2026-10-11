package com.example.board.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.example.board.auth.AuthUser;

/**
 * 조회수 집계의 방문자 키 (TASK-9#3, B2). 로그인 사용자는 {@code u:<user id>}(세션·Bearer·기기와 무관). 익명은
 * {@code s:<SHA-256(VID)>} — 익명 방문자에게 HTTP 세션을 만들지 않으려고 세션 대신 짧은 랜덤 쿠키 {@code VID}(128비트,
 * HttpOnly, SameSite=Lax, 30일)를 쓴다. DB 에는 쿠키 원문이 아니라 해시만 남는다. 쿠키가 없거나 형식이 틀리면 새로 발급한다.
 *
 * <p>쿠키를 받지 않는 클라이언트(봇 등)는 요청마다 새 방문자라 매번 집계된다(README 의 알려진 한계).
 */
@Component
public class ViewerKeys {

	public static final String COOKIE = "VID";

	static final Duration COOKIE_MAX_AGE = Duration.ofDays(30);

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{22}");

	private final SecureRandom random = new SecureRandom();

	private final boolean secure;

	public ViewerKeys(@Value("${app.security.cookie-secure:false}") boolean secure) {
		this.secure = secure;
	}

	/** 방문자 키. 익명이고 유효한 VID 가 없으면 새로 만들어 응답에 쿠키로 싣는다. */
	public String resolve(AuthUser viewer, HttpServletRequest request, HttpServletResponse response) {
		if (viewer != null) {
			return "u:" + viewer.id();
		}
		String vid = existing(request);
		if (vid == null) {
			vid = issue(response);
		}
		return "s:" + sha256(vid);
	}

	private static String existing(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (COOKIE.equals(cookie.getName()) && cookie.getValue() != null && VALID.matcher(cookie.getValue()).matches()) {
				return cookie.getValue();
			}
		}
		return null;
	}

	private String issue(HttpServletResponse response) {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		String vid = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		ResponseCookie cookie = ResponseCookie.from(COOKIE, vid).httpOnly(true).secure(secure).sameSite("Lax").path("/")
			.maxAge(COOKIE_MAX_AGE).build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
		return vid;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
