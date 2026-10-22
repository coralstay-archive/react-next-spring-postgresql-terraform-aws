package com.example.board.common;

import static com.example.board.common.ErrorContractTest.assertContract;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * TASK-20#2 (BE-2) 사용자 입력 때문에 500 이 나지 않는다(B2). 문자열 입력의 NUL·제어문자(탭·개행·CR 제외)는 400 validation,
 * 남은 DataIntegrityViolation 은 SQLState 로 409 conflict / 400 validation.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InputIntegrityTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	String bearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		bearer = fixtures.bearer(fixtures.user());
	}

	private HttpResponse<String> createPost(String title, String content) {
		return api.post("/posts")
			.bearer(bearer)
			.json("{\"title\":" + AuthClient.quote(title) + ",\"content\":" + AuthClient.quote(content) + "}")
			.send();
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 본문 문자열의 NUL 은 400 validation + 그 필드")
	void nulInBody() {
		JsonNode body = assertContract(createPost("제목", "본문\u0000끝"), 400, "validation");
		assertThat(body.get("fields").has("content")).as(body.toString()).isTrue();
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 탭·개행·CR 이 아닌 제어문자(\\u0001, \\u001B, \\u007F)도 400 validation")
	void otherControlCharacters() {
		for (String bad : new String[] { "\u0001", "\u001B", "\u007F" }) {
			JsonNode body = assertContract(createPost("제목" + bad, "본문"), 400, "validation");
			assertThat(body.get("fields").has("title")).as(body.toString()).isTrue();
		}
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 탭·개행·CR 은 그대로 받는다")
	void whitespaceControlsAllowed() {
		HttpResponse<String> response = createPost("제목", "첫 줄\r\n둘째 줄\n\t들여쓰기");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		assertThat(AuthClient.json(response).get("content").asString()).isEqualTo("첫 줄\r\n둘째 줄\n\t들여쓰기");
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 가입 닉네임·댓글 등 다른 본문 문자열도 같다")
	void otherBodies() {
		HttpResponse<String> signup = new AuthClient(api).signup(AuthClient.uniqueEmail(), AuthClient.PASSWORD,
				"닉\u0000네임");
		assertThat(assertContract(signup, 400, "validation").get("fields").has("nickname")).isTrue();
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 쿼리 파라미터(q·tag·category·관리자 q)의 NUL 은 400 validation")
	void nulInQuery() {
		for (String query : new String[] { "q=ab%00cd", "tag=a%00b", "category=a%00b" }) {
			JsonNode body = assertContract(api.get("/posts?" + query).send(), 400, "validation");
			assertThat(body.get("fields").has(query.substring(0, query.indexOf('=')))).as(body.toString()).isTrue();
		}
		String admin = fixtures.bearer(fixtures.admin());
		assertContract(api.get("/admin/users?q=a%00b").bearer(admin).send(), 400, "validation");
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 남은 유니크 위반(23505)은 409 conflict, 내부 메시지 없음")
	void uniqueViolation() {
		assertContract(api.get("/test/errors/db?kind=unique").bearer(bearer).send(), 409, "conflict");
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 남은 데이터 오류(22xxx)는 400 validation")
	void dataException() {
		assertContract(api.get("/test/errors/db?kind=data").bearer(bearer).send(), 400, "validation");
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 FK 위반(23503)은 409 conflict")
	void foreignKeyViolation() {
		assertContract(api.get("/test/errors/db?kind=fk").bearer(bearer).send(), 409, "conflict");
	}

	@Test
	@DisplayName("TASK-20#2 NOT NULL(23502)·CHECK(23514) 위반은 앞단 검증이 놓친 서버 버그이므로 500 internal_error")
	void serverBugViolations() {
		assertContract(api.get("/test/errors/db?kind=not-null").bearer(bearer).send(), 500, "internal_error");
		assertContract(api.get("/test/errors/db?kind=check").bearer(bearer).send(), 500, "internal_error");
	}

	@Test
	@DisplayName("TASK-20#2 무결성 위반 로그에는 SQLState·제약 이름만 남기고 DB 메시지(키 값 등 개인정보)는 남기지 않는다")
	void integrityLogHasNoValues(CapturedOutput output) {
		HttpResponse<String> response = api.get("/test/errors/db?kind=unique-pii").bearer(bearer).send();
		assertContract(response, 409, "conflict");
		assertThat(output.getAll()).contains("23505").doesNotContain("Key (slug)=(pii-");
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 리스트 원소 문자열의 제어 문자는 fields 에 tags[i] 경로")
	void controlCharacterInListElement() {
		JsonNode body = assertContract(api.post("/posts")
			.bearer(bearer)
			.json("{\"title\":\"t\",\"content\":\"c\",\"tags\":[" + AuthClient.quote("a\u0000b") + "]}")
			.send(), 400, "validation");
		assertThat(body.get("fields").has("tags[0]")).as(body.toString()).isTrue();
	}

	@Test
	@DisplayName("TASK-20#2 TASK-20#B2 SQLState 를 모르는 무결성 위반은 409 conflict")
	void plainIntegrityViolation() {
		assertContract(api.get("/test/errors/db?kind=plain").bearer(bearer).send(), 409, "conflict");
	}

}
