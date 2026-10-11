package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** TASK-3#1 POST /api/auth/signup: 이메일 형식·비밀번호 정책·닉네임 검증, LOCAL 계정 생성. */
@IntegrationTest
class SignupTest {

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

	@Test
	@DisplayName("TASK-3#1 가입하면 201 과 사용자 요약을 돌려주고 users·LOCAL identity(소문자 이메일)·local_credentials 를 만든다")
	void signupCreatesLocalAccount() {
		String email = AuthClient.uniqueEmail();
		String mixedCase = "  " + email.toUpperCase().replace("USER-", "User-") + " ";

		HttpResponse<String> response = auth.signup(mixedCase, "passw0rd!", " 홍길동 ");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		JsonNode body = AuthClient.json(response);
		long id = body.get("id").asLong();
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.get("nickname").asString()).isEqualTo("홍길동");
		assertThat(body.get("role").asString()).isEqualTo("USER");
		assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(body.has("password")).isFalse();

		Map<String, Object> user = jdbc.queryForMap("select nickname, email, role, status from users where id = ?", id);
		assertThat(user).containsEntry("nickname", "홍길동").containsEntry("email", email).containsEntry("role", "USER");
		Map<String, Object> identity = jdbc
			.queryForMap("select id, provider, subject, email from user_identities where user_id = ?", id);
		assertThat(identity).containsEntry("provider", "LOCAL").containsEntry("subject", email);
		assertThat(jdbc.queryForObject("select count(*) from local_credentials where identity_id = ?", Integer.class,
				identity.get("id")))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-3#1 가입은 자동 로그인이 아니다: 세션 쿠키를 만들지 않는다")
	void signupDoesNotLogIn() {
		HttpResponse<String> response = auth.signup(AuthClient.uniqueEmail(), "passw0rd!", "tester");

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(Api.setCookie(response, "SESSION")).isEmpty();
	}

	@Test
	@DisplayName("TASK-3#1 가입은 공개지만 세션 경로라 CSRF 토큰이 없으면 403")
	void signupRequiresCsrf() {
		HttpResponse<String> response = api.post("/auth/signup")
			.json(AuthClient.signupBody(AuthClient.uniqueEmail(), "passw0rd!", "tester"))
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"error\":\"csrf\"");
	}

	@ParameterizedTest(name = "TASK-3#1 {3} → 400")
	@CsvSource(delimiter = '|', textBlock = """
			not-an-email           | passw0rd!  | tester | 이메일 형식 아님
			a@b                    | passw0rd!  | tester | 도메인에 점 없음
			''                     | passw0rd!  | tester | 이메일 빈 값
			EMAIL                  | short1     | tester | 비밀번호 8자 미만
			EMAIL                  | onlyletters | tester | 비밀번호에 숫자 없음
			EMAIL                  | 1234567890 | tester | 비밀번호에 문자 없음
			EMAIL                  | LONG73     | tester | 비밀번호 72바이트 초과
			EMAIL                  | MULTIBYTE  | tester | 비밀번호 72바이트 초과(멀티바이트)
			EMAIL                  | passw0rd!  | a      | 닉네임 2자 미만
			EMAIL                  | passw0rd!  | '   '  | 닉네임 공백만
			EMAIL                  | passw0rd!  | NICK21 | 닉네임 20자 초과
			""")
	@DisplayName("TASK-3#1 입력 검증 실패는 400 과 필드 오류 JSON")
	void validation(String email, String password, String nickname, String label) {
		String actualEmail = "EMAIL".equals(email) ? AuthClient.uniqueEmail() : email;
		String actualPassword = switch (password) {
			case "LONG73" -> "a1".repeat(36) + "b";
			case "MULTIBYTE" -> "가".repeat(24) + "a1";
			default -> password;
		};
		String actualNickname = "NICK21".equals(nickname) ? "가".repeat(21) : nickname;

		HttpResponse<String> response = auth.signup(actualEmail, actualPassword, actualNickname);

		assertThat(response.statusCode()).as("%s: %s", label, response.body()).isEqualTo(400);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("error").asString()).isEqualTo("validation");
		assertThat(body.get("fields").isObject()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from user_identities where subject = ?", Integer.class,
				actualEmail.strip().toLowerCase()))
			.isZero();
	}

	@Test
	@DisplayName("TASK-3#1 경계값: 비밀번호 8자·72바이트, 닉네임 2자·20자는 허용")
	void boundaries() {
		assertThat(auth.signup(AuthClient.uniqueEmail(), "abcdefg1", "ab").statusCode()).isEqualTo(201);
		assertThat(auth.signup(AuthClient.uniqueEmail(), "a1".repeat(36), "가".repeat(20)).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-3#1 본문이 JSON 이 아니면 400")
	void malformedBody() {
		HttpResponse<String> response = api.post("/auth/signup").csrf(auth.csrfToken()).json("{not json").send();

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-3#1 TASK-3#B1 같은 이메일(대소문자 무시)의 LOCAL 가입은 한 번만: 중복은 409")
	void duplicateEmailIs409() {
		String email = auth.signedUp();

		HttpResponse<String> response = auth.signup(email.toUpperCase(), "an0therpass", "other");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("email_taken");
		assertThat(jdbc.queryForObject(
				"select count(*) from user_identities where provider = 'LOCAL' and lower(subject) = ?", Integer.class,
				email))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-3#1 TASK-3#B1 동시에 같은 이메일로 가입해도 LOCAL identity 는 하나다(나머지는 409)")
	void concurrentDuplicateSignup() throws Exception {
		String email = AuthClient.uniqueEmail();
		var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
		try {
			var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
			for (int i = 0; i < 4; i++) {
				futures.add(executor.submit(() -> auth.signup(email, "passw0rd!", "tester").statusCode()));
			}
			var statuses = new java.util.ArrayList<Integer>();
			for (var future : futures) {
				statuses.add(future.get());
			}
			assertThat(statuses).containsOnly(201, 409);
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("select count(*) from user_identities where subject = ?", Integer.class, email))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
	}

}
