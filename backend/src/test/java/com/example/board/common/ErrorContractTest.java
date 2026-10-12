package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.ErrorContractTestController;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * TASK-20#1 (BE-1) 오류 응답 계약: 프레임워크가 내는 405·415·406·404·500 과 컨트롤러 밖(/error) 오류도 모두
 * {@code {status, error, message}} 모양과 snake_case 코드다. 내부 메시지·스택트레이스는 응답에 없다(B1).
 */
@IntegrationTest
class ErrorContractTest {

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

	/** B1: 모양은 status·error·message(+선택 fields)뿐이고 내부 정보가 없다. */
	static JsonNode assertContract(HttpResponse<String> response, int status, String error) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("error").asString()).isEqualTo(error).matches("[a-z][a-z0-9_]*");
		assertThat(body.get("message").asString()).isNotBlank();
		assertThat(body.propertyNames()).isSubsetOf(List.of("status", "error", "message", "fields"));
		assertThat(response.body()).doesNotContain(ErrorContractTestController.SECRET)
			.doesNotContain("Exception")
			.doesNotContain("at com.")
			.doesNotContain("at org.");
		return body;
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 지원하지 않는 메서드는 405 method_not_allowed + Allow 헤더")
	void methodNotAllowed() {
		HttpResponse<String> response = api.delete("/categories").bearer(bearer).send();
		assertContract(response, 405, "method_not_allowed");
		assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(allow -> assertThat(allow).contains("GET"));
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 JSON 이 아닌 본문은 415 unsupported_media_type")
	void unsupportedMediaType() {
		HttpResponse<String> response = api.post("/posts")
			.bearer(bearer)
			.header("Content-Type", "text/plain")
			.send();
		assertContract(response, 415, "unsupported_media_type");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 JSON 을 받지 않는 Accept 는 406 not_acceptable (본문은 그래도 JSON)")
	void notAcceptable() {
		HttpResponse<String> response = api.get("/categories").header("Accept", "text/html").send();
		assertContract(response, 406, "not_acceptable");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 없는 경로는 (인증된 사용자에게) 404 not_found")
	void notFound() {
		assertContract(api.get("/no-such-endpoint").bearer(bearer).send(), 404, "not_found");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 ResponseStatusException 도 같은 모양(404 not_found)")
	void responseStatusException() {
		assertContract(api.patch("/test/resources/987654321").bearer(bearer).send(), 404, "not_found");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 처리되지 않은 예외는 500 internal_error, 내부 메시지를 노출하지 않는다")
	void unhandled() {
		assertContract(api.get("/test/errors/boom").bearer(bearer).send(), 500, "internal_error");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 필터(컨트롤러 밖)에서 난 예외도 /error 를 거쳐 500 internal_error JSON")
	void filterFailure() {
		HttpResponse<String> response = api.get(ErrorContractTestController.FailingFilter.PATH).bearer(bearer).send();
		assertContract(response, 500, "internal_error");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 컨테이너 sendError(/error 경로)도 같은 모양과 snake_case 코드")
	void containerError() {
		assertContract(api.get("/test/errors/send-error?status=503").bearer(bearer).send(), 503, "service_unavailable");
		assertContract(api.get("/test/errors/send-error?status=500").bearer(bearer).send(), 500, "internal_error");
		assertContract(api.get("/test/errors/send-error?status=404").bearer(bearer).send(), 404, "not_found");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 /error 를 직접 불러도 같은 모양")
	void errorPathDirect() {
		HttpResponse<String> response = api.get("/error").send();
		JsonNode body = AuthClient.json(response);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("status", "error", "message");
		assertThat(body.get("error").asString()).matches("[a-z][a-z0-9_]*");
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 필수 파라미터 누락은 400 validation + fields")
	void missingParameter() {
		JsonNode body = assertContract(api.get("/test/errors/required-param").bearer(bearer).send(), 400, "validation");
		assertThat(body.get("fields").has("count")).isTrue();
	}

	@Test
	@DisplayName("TASK-20#1 TASK-20#B1 보안 체인의 401·403 과 @PreAuthorize 403 은 그대로다")
	void securityErrorsUnchanged() {
		assertContract(api.get("/test/whoami").send(), 401, "unauthorized");
		assertContract(api.get("/test/admin-only").bearer(bearer).send(), 403, "forbidden");
	}

}
