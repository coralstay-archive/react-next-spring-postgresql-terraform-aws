package com.example.board.support;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-3 인증 API 를 실제 HTTP 로 부르는 테스트 헬퍼. 쿠키 jar 가 없으므로 세션·CSRF 쿠키는 호출자가 명시적으로 넘긴다.
 *
 * <p>가입·로그인은 인스턴스마다 다른 클라이언트 IP 를 신뢰 헤더({@code CloudFront-Viewer-Address})로 보낸다(TASK-22#1). 모든 요청이
 * 127.0.0.1 에서 오므로, 그러지 않으면 IP 별 가입·로그인 실패 한도를 테스트끼리 나눠 쓰게 된다.
 */
public final class AuthClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	public static final String PASSWORD = "passw0rd!";

	private final Api api;

	private String ip = PhoneClient.uniqueIp();

	public AuthClient(Api api) {
		this.api = api;
	}

	/** 이후 가입·로그인 요청의 클라이언트 IP. */
	public AuthClient ip(String ip) {
		this.ip = ip;
		return this;
	}

	public String ip() {
		return ip;
	}

	/** 요청에 이 클라이언트의 IP 를 신뢰 헤더로 붙인다. */
	public Api.Request withIp(Api.Request request) {
		return request.header(PhoneClient.IP_HEADER, ip + ":" + ThreadLocalRandom.current().nextInt(1024, 65535));
	}

	public static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	/** 테스트마다 겹치지 않는 이메일. */
	public static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	public static String quote(String value) {
		return JSON.writeValueAsString(value);
	}

	public static String signupBody(String email, String password, String nickname) {
		return "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + ",\"nickname\":" + quote(nickname)
				+ "}";
	}

	public static String loginBody(String email, String password) {
		return "{\"email\":" + quote(email) + ",\"password\":" + quote(password) + "}";
	}

	/** {@code GET /api/auth/csrf} 로 새 XSRF-TOKEN 값을 받는다. */
	public String csrfToken() {
		return Api.cookie(api.get("/auth/csrf").send(), "XSRF-TOKEN").orElseThrow();
	}

	public HttpResponse<String> signup(String email, String password, String nickname) {
		return withIp(api.post("/auth/signup")).csrf(csrfToken()).json(signupBody(email, password, nickname)).send();
	}

	/** 가입하고 이메일을 돌려준다. 실패하면 예외. */
	public String signedUp(String email) {
		HttpResponse<String> response = signup(email, PASSWORD, "tester");
		if (response.statusCode() != 201) {
			throw new IllegalStateException("signup failed: " + response.statusCode() + " " + response.body());
		}
		return email;
	}

	public String signedUp() {
		return signedUp(uniqueEmail());
	}

	public HttpResponse<String> sessionLogin(String email, String password) {
		return withIp(api.post("/auth/login")).csrf(csrfToken()).json(loginBody(email, password)).send();
	}

	/** 세션 로그인 후 {@code SESSION=...} 쿠키 문자열. */
	public String sessionCookie(String email) {
		HttpResponse<String> response = sessionLogin(email, PASSWORD);
		if (response.statusCode() != 200) {
			throw new IllegalStateException("login failed: " + response.statusCode() + " " + response.body());
		}
		return "SESSION=" + Api.cookie(response, "SESSION").orElseThrow();
	}

	public HttpResponse<String> jwtLogin(String email, String password) {
		return withIp(api.post("/auth/login?token=jwt")).json(loginBody(email, password)).send();
	}

	/** JWT 로그인 응답 본문(accessToken, refreshToken, ...). */
	public JsonNode tokens(String email) {
		HttpResponse<String> response = jwtLogin(email, PASSWORD);
		if (response.statusCode() != 200) {
			throw new IllegalStateException("jwt login failed: " + response.statusCode() + " " + response.body());
		}
		return json(response);
	}

	public HttpResponse<String> refresh(String refreshToken) {
		return api.post("/auth/token/refresh").json("{\"refreshToken\":" + quote(refreshToken) + "}").send();
	}

}
