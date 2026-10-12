package com.example.board.common;

import static com.example.board.common.ErrorContractTest.assertContract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.admin.AdminCategoryRequests;
import com.example.board.admin.AdminRequests;
import com.example.board.attachment.AttachmentRequests;
import com.example.board.auth.StepUpLocalRequest;
import com.example.board.auth.local.LoginRequest;
import com.example.board.auth.local.SignupRequest;
import com.example.board.auth.phone.PhoneOtpRequest;
import com.example.board.auth.phone.PhoneVerifyRequest;
import com.example.board.auth.token.RefreshRequest;
import com.example.board.comment.CommentRequest;
import com.example.board.post.PostRequests;
import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TASK-20#3 (BE-5) 요청 본문은 엄격하게 읽는다: 알 수 없는 필드는 400 validation(그 이름이 fields 에), 타입 오류도 fields 에
 * 경로를 담는다. 프론트가 보내는 본문 모양은 그대로 통과해야 한다(회귀 방지).
 */
@IntegrationTest
class RequestBodyStrictnessTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	/** 앱(Spring MVC)이 본문을 읽을 때 쓰는 매퍼. */
	@Autowired
	JsonMapper jsonMapper;

	Api api;

	String bearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		bearer = fixtures.bearer(fixtures.user());
	}

	private JsonNode validation(HttpResponse<String> response, String field) {
		JsonNode body = assertContract(response, 400, "validation");
		assertThat(body.get("fields").has(field)).as(body.toString()).isTrue();
		return body;
	}

	@Test
	@DisplayName("TASK-20#3 알 수 없는 필드는 400 validation, fields 에 그 이름")
	void unknownFieldOnCreate() {
		validation(api.post("/posts").bearer(bearer).json("{\"title\":\"t\",\"content\":\"c\",\"titel\":\"오타\"}").send(),
				"titel");
	}

	@Test
	@DisplayName("TASK-20#3 오타 PATCH 는 200 무변경이 아니라 400 validation 이고 아무것도 바꾸지 않는다")
	void typoPatchRejected() {
		String slug = board.category();
		long id = jdbc.queryForObject("select id from categories where slug = ?", Long.class, slug);
		String admin = fixtures.bearer(fixtures.admin());

		validation(api.patch("/admin/categories/" + id).bearer(admin).json("{\"nmae\":\"새 이름\"}").send(), "nmae");
		assertThat(jdbc.queryForObject("select name from categories where id = ?", String.class, id))
			.isEqualTo("카테고리 " + slug);
	}

	@Test
	@DisplayName("TASK-20#3 타입 오류는 400 validation, fields 에 경로(리스트 원소는 [i])")
	void typeErrorsHaveFields() {
		validation(api.post("/posts")
			.bearer(bearer)
			.json("{\"title\":\"t\",\"content\":\"c\",\"attachmentIds\":[1,\"abc\"]}")
			.send(), "attachmentIds[1]");
		String admin = fixtures.bearer(fixtures.admin());
		validation(api.post("/admin/categories").bearer(admin).json("{\"slug\":\"ab-cd\",\"name\":\"n\",\"sortOrder\":\"x\"}").send(),
				"sortOrder");
		validation(api.post("/admin/categories").bearer(admin).json("{\"slug\":\"ab-cd\",\"name\":\"n\",\"adminOnly\":\"maybe\"}").send(),
				"adminOnly");
		validation(api.post("/posts").bearer(bearer).json("{\"title\":\"t\",\"content\":\"c\",\"tags\":{\"a\":1}}").send(),
				"tags");
	}

	@Test
	@DisplayName("TASK-20#3 문법이 깨진 JSON 은 필드가 없으므로 400 bad_request")
	void malformedJson() {
		assertContract(api.post("/posts").bearer(bearer).json("{\"title\":").send(), 400, "bad_request");
	}

	/** 프론트(frontend/src)가 실제로 보내는 본문 모양. 하나라도 알 수 없는 필드로 거부되면 회귀다. */
	@Test
	@DisplayName("TASK-20#3 프론트가 보내는 모든 요청 본문 모양은 그대로 읽힌다(회귀 방지)")
	void frontendBodiesStillAccepted() {
		Map<Class<?>, String> bodies = Map.ofEntries(
				Map.entry(SignupRequest.class, "{\"email\":\"a@b.co\",\"password\":\"passw0rd!\",\"nickname\":\"닉\"}"),
				Map.entry(LoginRequest.class, "{\"email\":\"a@b.co\",\"password\":\"passw0rd!\"}"),
				Map.entry(StepUpLocalRequest.class, "{\"password\":\"passw0rd!\"}"),
				Map.entry(PhoneOtpRequest.class, "{\"phone\":\"01012345678\"}"),
				Map.entry(PhoneVerifyRequest.class, "{\"challengeId\":\"x\",\"code\":\"123456\"}"),
				Map.entry(RefreshRequest.class, "{\"refreshToken\":\"r\"}"),
				Map.entry(CommentRequest.class, "{\"content\":\"댓글\"}"),
				Map.entry(PostRequests.Create.class,
						"{\"title\":\"t\",\"content\":\"c\",\"categorySlug\":\"free\",\"tags\":[\"a\"],\"attachmentIds\":[1]}"),
				Map.entry(PostRequests.Update.class,
						"{\"title\":\"t\",\"content\":\"c\",\"categorySlug\":\"\",\"tags\":[],\"attachmentIds\":[]}"),
				Map.entry(AdminRequests.ReasonRequest.class, "{\"reason\":\"사유\"}"),
				Map.entry(AdminCategoryRequests.Create.class,
						"{\"slug\":\"ab\",\"name\":\"n\",\"adminOnly\":false,\"sortOrder\":3}"),
				Map.entry(AdminCategoryRequests.Update.class, "{\"slug\":\"ab\",\"name\":\"n\",\"adminOnly\":true,\"sortOrder\":1}"),
				Map.entry(AttachmentRequests.Presign.class,
						"{\"contentType\":\"image/png\",\"size\":10,\"filename\":\"a.png\"}"));
		bodies.forEach((type, json) -> assertThatCode(() -> jsonMapper.readValue(json, type)).as(type.getName())
			.doesNotThrowAnyException());
		// 빈 사유 본문 {} 도 그대로
		assertThatCode(() -> jsonMapper.readValue("{}", AdminRequests.ReasonRequest.class)).doesNotThrowAnyException();
		// 매퍼 자체가 알 수 없는 필드를 거부하는지(위 검사가 의미 있는지)
		assertThatCode(() -> jsonMapper.readValue("{\"content\":\"c\",\"x\":1}", CommentRequest.class))
			.isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
	}

}
