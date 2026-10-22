package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;

/**
 * TASK-24#5 (BE-11) 역직렬화할 수 없는 세션(배포 뒤 클래스가 바뀜, 깨진 바이트)은 500 이 아니라 세션 없음·만료와 같다: 그 세션을 지우고
 * (쿠키 만료) 익명으로 계속한다. 보호된 API 는 표준 401 {@code unauthorized} JSON, 공개 GET 은 200.
 */
@IntegrationTest
class CorruptSessionTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private static String sessionId(String cookie) {
		return new String(Base64.getDecoder().decode(cookie.substring("SESSION=".length())), StandardCharsets.UTF_8);
	}

	/** 로그인 세션의 SecurityContext 바이트를 {@code corrupt} 로 바꾼 쿠키. */
	private String corruptedSession(UnaryOperator<byte[]> corrupt) {
		String cookie = auth.sessionCookie(auth.signedUp());
		String where = " where attribute_name = ? and session_primary_id ="
				+ " (select primary_id from spring_session where session_id = ?)";
		byte[] bytes = jdbc.queryForObject("select attribute_bytes from spring_session_attributes" + where,
				byte[].class, HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, sessionId(cookie));
		int updated = jdbc.update("update spring_session_attributes set attribute_bytes = ?" + where,
				corrupt.apply(bytes), HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				sessionId(cookie));
		assertThat(updated).isEqualTo(1);
		return cookie;
	}

	/** 직렬화 스트림 속 principal 클래스 이름을 같은 길이의 없는 이름으로 — 배포 뒤 클래스가 바뀐(사라진) 경우. */
	private static byte[] renameAuthUserClass(byte[] bytes) {
		byte[] from = "com.example.board.auth.AuthUser".getBytes(StandardCharsets.UTF_8);
		byte[] to = "com.example.board.auth.AuthUsex".getBytes(StandardCharsets.UTF_8);
		byte[] result = bytes.clone();
		int replaced = 0;
		for (int i = 0; i + from.length <= result.length; i++) {
			boolean match = true;
			for (int j = 0; j < from.length && match; j++) {
				match = result[i + j] == from[j];
			}
			if (match) {
				System.arraycopy(to, 0, result, i, to.length);
				replaced++;
			}
		}
		assertThat(replaced).isPositive();
		return result;
	}

	private void assertInvalidated(String cookie, HttpResponse<String> response) {
		assertThat(Api.setCookie(response, "SESSION"))
			.hasValueSatisfying(set -> assertThat(set).startsWith("SESSION=;").containsIgnoringCase("Max-Age=0"));
		assertThat(jdbc.queryForObject("select count(*) from spring_session where session_id = ?", Integer.class,
				sessionId(cookie)))
			.isZero();
	}

	@Test
	@DisplayName("TASK-24#5 클래스가 바뀌어 역직렬화되지 않는 세션: 보호된 API 는 401 unauthorized JSON, 세션 삭제 + 쿠키 만료")
	void classChangedSessionIs401() {
		String cookie = corruptedSession(CorruptSessionTest::renameAuthUserClass);

		HttpResponse<String> response = api.get("/auth/me").cookie(cookie).send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
		assertThat(AuthClient.json(response).get("status").asInt()).isEqualTo(401);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("unauthorized");
		assertInvalidated(cookie, response);
	}

	@Test
	@DisplayName("TASK-24#5 깨진 바이트의 세션도 같다: 401 unauthorized, 세션 삭제")
	void garbageSessionIs401() {
		String cookie = corruptedSession(bytes -> new byte[] { 0, 1, 2, 3 });

		HttpResponse<String> response = api.get("/auth/me").cookie(cookie).send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("unauthorized");
		assertInvalidated(cookie, response);
	}

	@Test
	@DisplayName("TASK-24#5 역직렬화되지 않는 세션을 붙인 공개 GET 은 익명으로 200, 세션은 지운다")
	void publicGetWithCorruptSessionIsAnonymous() {
		String cookie = corruptedSession(CorruptSessionTest::renameAuthUserClass);

		HttpResponse<String> response = api.get("/posts").cookie(cookie).send();

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertInvalidated(cookie, response);
	}

}
