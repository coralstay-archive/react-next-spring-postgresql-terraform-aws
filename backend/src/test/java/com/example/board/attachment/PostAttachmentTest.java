package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;
import com.example.board.user.User;

import tools.jackson.databind.JsonNode;

/**
 * TASK-11#2 글 작성·수정의 attachmentIds 로 확정된 첨부를 글에 연결하고 상세에 attachments 를 보여 준다.
 * TASK-11#B2 본인이 업로드한 첨부만 자기 글에 연결할 수 있다.
 */
@IntegrationTest
class PostAttachmentTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	private static String ids(long... ids) {
		return LongStream.of(ids).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));
	}

	private HttpResponse<String> create(String bearer, long... attachmentIds) {
		return api.post("/posts").bearer(bearer)
			.json("{\"title\":\"사진\",\"content\":\"본문\",\"attachmentIds\":" + ids(attachmentIds) + "}").send();
	}

	private HttpResponse<String> patch(String bearer, long postId, String json) {
		return api.patch("/posts/" + postId).bearer(bearer).json(json).send();
	}

	private static List<Long> attachmentIds(JsonNode post) {
		return post.get("attachments").valueStream().map(a -> a.get("id").asLong()).toList();
	}

	private Long postIdOf(long attachmentId) {
		return jdbc.queryForObject("select post_id from attachments where id = ?", Long.class, attachmentId);
	}

	private long postWithoutAttachments(String bearer) {
		HttpResponse<String> response = api.post("/posts").bearer(bearer).json("{\"title\":\"t\",\"content\":\"c\"}").send();
		assertThat(response.statusCode()).isEqualTo(201);
		return AuthClient.json(response).get("id").asLong();
	}

	@Test
	@DisplayName("TASK-11#2 글 작성에 attachmentIds → 연결되고 상세 attachments[{id,url,contentType,size}] (올린 순)")
	void createWithAttachments() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		long first = uploads.uploaded(bearer, "image/png", UploadClient.bytes(100));
		long second = uploads.uploaded(bearer, "image/gif", UploadClient.bytes(50));

		HttpResponse<String> response = create(bearer, second, first, first);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		JsonNode created = AuthClient.json(response);
		long postId = created.get("id").asLong();
		assertThat(attachmentIds(created)).containsExactly(first, second);
		assertThat(postIdOf(first)).isEqualTo(postId);

		JsonNode read = AuthClient.json(api.get("/posts/" + postId).send());
		assertThat(attachmentIds(read)).containsExactly(first, second);
		JsonNode attachment = read.get("attachments").get(0);
		String key = jdbc.queryForObject("select s3_key from attachments where id = ?", String.class, first);
		assertThat(attachment.get("url").asString()).isEqualTo("/uploads/" + key.substring("uploads/".length()))
			.startsWith("/uploads/" + user.getId() + "/");
		assertThat(attachment.get("contentType").asString()).isEqualTo("image/png");
		assertThat(attachment.get("size").asLong()).isEqualTo(100L);

		JsonNode plain = AuthClient.json(api.get("/posts/" + postWithoutAttachments(bearer)).send());
		assertThat(plain.get("attachments").isArray()).isTrue();
		assertThat(plain.get("attachments").size()).isZero();
	}

	@Test
	@DisplayName("TASK-11#2 수정: attachmentIds 를 주면 그 목록으로 바꾸고(빠진 것은 연결 해제), 생략하면 그대로 둔다")
	void patchReplacesAttachments() {
		String bearer = fixtures.bearer(fixtures.user());
		long a = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long b = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long postId = AuthClient.json(create(bearer, a)).get("id").asLong();

		HttpResponse<String> titleOnly = patch(bearer, postId, "{\"title\":\"새 제목\"}");
		assertThat(titleOnly.statusCode()).isEqualTo(200);
		assertThat(attachmentIds(AuthClient.json(titleOnly))).containsExactly(a);

		HttpResponse<String> replaced = patch(bearer, postId, "{\"attachmentIds\":" + ids(b, a) + "}");
		assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
		assertThat(attachmentIds(AuthClient.json(replaced))).containsExactly(a, b);

		HttpResponse<String> onlyB = patch(bearer, postId, "{\"attachmentIds\":" + ids(b) + "}");
		assertThat(attachmentIds(AuthClient.json(onlyB))).containsExactly(b);
		assertThat(postIdOf(a)).isNull();

		HttpResponse<String> cleared = patch(bearer, postId, "{\"attachmentIds\":[]}");
		assertThat(attachmentIds(AuthClient.json(cleared))).isEmpty();
		assertThat(postIdOf(b)).isNull();

		// 연결 해제된 첨부는 다시 (다른 글에도) 붙일 수 있다
		assertThat(create(bearer, a, b).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("TASK-11#B2 남이 올린 첨부는 403 attachment_forbidden — 글은 만들어지지 않는다")
	void othersAttachmentForbidden() {
		long othersAttachment = uploads.uploaded(fixtures.bearer(fixtures.user()), "image/png", UploadClient.bytes(10));
		User me = fixtures.user();
		String bearer = fixtures.bearer(me);
		long mine = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));

		HttpResponse<String> response = create(bearer, mine, othersAttachment);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(error(response)).isEqualTo("attachment_forbidden");
		assertThat(jdbc.queryForObject("select count(*) from posts where author_id = ?", Integer.class, me.getId())).isZero();
		assertThat(postIdOf(mine)).as("롤백").isNull();

		long postId = postWithoutAttachments(bearer);
		HttpResponse<String> patched = patch(bearer, postId, "{\"attachmentIds\":" + ids(othersAttachment) + "}");
		assertThat(patched.statusCode()).isEqualTo(403);
		assertThat(error(patched)).isEqualTo("attachment_forbidden");
		assertThat(postIdOf(othersAttachment)).isNull();
	}

	@Test
	@DisplayName("TASK-11#B2 ADMIN 이 남의 글을 고칠 때: 이미 붙은 첨부는 유지·해제할 수 있지만 다른 사람(ADMIN 본인 포함)의 첨부는 붙일 수 없다")
	void adminCannotAttachToOthersPost() {
		String authorBearer = fixtures.bearer(fixtures.user());
		long a = uploads.uploaded(authorBearer, "image/png", UploadClient.bytes(10));
		long postId = AuthClient.json(create(authorBearer, a)).get("id").asLong();

		String adminBearer = fixtures.bearer(fixtures.admin());
		long adminsOwn = uploads.uploaded(adminBearer, "image/png", UploadClient.bytes(10));
		HttpResponse<String> denied = patch(adminBearer, postId, "{\"attachmentIds\":" + ids(a, adminsOwn) + "}");
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(error(denied)).isEqualTo("attachment_forbidden");

		HttpResponse<String> keep = patch(adminBearer, postId, "{\"title\":\"관리자 수정\",\"attachmentIds\":" + ids(a) + "}");
		assertThat(keep.statusCode()).as(keep.body()).isEqualTo(200);
		assertThat(attachmentIds(AuthClient.json(keep))).containsExactly(a);

		HttpResponse<String> remove = patch(adminBearer, postId, "{\"attachmentIds\":[]}");
		assertThat(remove.statusCode()).isEqualTo(200);
		assertThat(postIdOf(a)).isNull();
	}

	@Test
	@DisplayName("TASK-11#B2 확정 전(PENDING) 첨부는 400 attachment_not_confirmed, 다른 글에 붙은 첨부는 400 attachment_in_use, 없는 id 는 400 unknown_attachment")
	void onlyConfirmedUnattached() {
		String bearer = fixtures.bearer(fixtures.user());
		long pending = uploads.presigned(bearer, "image/png", 10).get("attachmentId").asLong();
		HttpResponse<String> notConfirmed = create(bearer, pending);
		assertThat(notConfirmed.statusCode()).isEqualTo(400);
		assertThat(error(notConfirmed)).isEqualTo("attachment_not_confirmed");

		long used = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long firstPost = AuthClient.json(create(bearer, used)).get("id").asLong();
		HttpResponse<String> inUse = create(bearer, used);
		assertThat(inUse.statusCode()).isEqualTo(400);
		assertThat(error(inUse)).isEqualTo("attachment_in_use");
		assertThat(postIdOf(used)).isEqualTo(firstPost);

		HttpResponse<String> unknown = create(bearer, Long.MAX_VALUE);
		assertThat(unknown.statusCode()).isEqualTo(400);
		assertThat(error(unknown)).isEqualTo("unknown_attachment");
	}

	private static JsonNode linkFields(HttpResponse<String> response) {
		JsonNode fields = AuthClient.json(response).get("fields");
		assertThat(fields).as(response.body()).isNotNull();
		assertThat(fields.isObject()).as(response.body()).isTrue();
		return fields;
	}

	@Test
	@DisplayName("TASK-31#4 글 연결 오류는 fields {\"attachmentIds[i]\": 문구} 로 요청의 몇 번째 첨부인지 알린다 — unknown_attachment·attachment_not_confirmed·attachment_in_use")
	void linkErrorsIdentifyIndex() {
		String bearer = fixtures.bearer(fixtures.user());
		long ok = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		long pending = uploads.presigned(bearer, "image/png", 10).get("attachmentId").asLong();
		HttpResponse<String> notConfirmed = create(bearer, ok, pending);
		assertThat(error(notConfirmed)).isEqualTo("attachment_not_confirmed");
		JsonNode fields = linkFields(notConfirmed);
		assertThat(fields.propertyNames()).containsExactly("attachmentIds[1]");
		assertThat(fields.get("attachmentIds[1]").asString()).contains("확정");

		long used = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		assertThat(create(bearer, used).statusCode()).isEqualTo(201);
		HttpResponse<String> inUse = create(bearer, used, ok);
		assertThat(error(inUse)).isEqualTo("attachment_in_use");
		assertThat(linkFields(inUse).propertyNames()).containsExactly("attachmentIds[0]");
		assertThat(linkFields(inUse).get("attachmentIds[0]").asString()).contains("다른 글");

		HttpResponse<String> unknown = create(bearer, ok, ok, Long.MAX_VALUE);
		assertThat(error(unknown)).isEqualTo("unknown_attachment");
		assertThat(linkFields(unknown).propertyNames()).as("중복을 세어 요청 배열의 위치").containsExactly("attachmentIds[2]");
		assertThat(linkFields(unknown).get("attachmentIds[2]").asString()).contains("없는 첨부");
		// 옛 클라이언트 호환: 메시지 끝의 id 는 그대로 둔다.
		assertThat(AuthClient.json(unknown).get("message").asString()).endsWith(Long.toString(Long.MAX_VALUE));

		long postId = postWithoutAttachments(bearer);
		HttpResponse<String> patched = patch(bearer, postId, "{\"attachmentIds\":" + ids(ok, Long.MAX_VALUE) + "}");
		assertThat(error(patched)).isEqualTo("unknown_attachment");
		assertThat(linkFields(patched).propertyNames()).containsExactly("attachmentIds[1]");
	}

	@Test
	@DisplayName("TASK-31#4 attachment_forbidden 도 fields attachmentIds[i] 로 위치를 알리되 문구는 일반적이다(남의 첨부인지 없는지를 더 드러내지 않는다)")
	void forbiddenIdentifiesIndex() {
		long othersAttachment = uploads.uploaded(fixtures.bearer(fixtures.user()), "image/png", UploadClient.bytes(10));
		String bearer = fixtures.bearer(fixtures.user());
		long mine = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));

		HttpResponse<String> response = create(bearer, mine, othersAttachment);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(error(response)).isEqualTo("attachment_forbidden");
		JsonNode fields = linkFields(response);
		assertThat(fields.propertyNames()).containsExactly("attachmentIds[1]");
		assertThat(fields.get("attachmentIds[1]").asString()).isEqualTo("본인이 올린 첨부만 쓸 수 있습니다")
			.doesNotContain(Long.toString(othersAttachment));
		assertThat(AuthClient.json(response).get("message").asString()).doesNotContain(Long.toString(othersAttachment));
	}

	@Test
	@DisplayName("TASK-11#2 글당 첨부는 10개까지(넘으면 400 validation), null 원소도 400")
	void limit() {
		String bearer = fixtures.bearer(fixtures.user());
		long[] eleven = LongStream.rangeClosed(1, 11).toArray();
		HttpResponse<String> tooMany = create(bearer, eleven);
		assertThat(tooMany.statusCode()).isEqualTo(400);
		assertThat(error(tooMany)).isEqualTo("validation");

		HttpResponse<String> nullElement = api.post("/posts").bearer(bearer)
			.json("{\"title\":\"t\",\"content\":\"c\",\"attachmentIds\":[null]}").send();
		assertThat(nullElement.statusCode()).isEqualTo(400);
	}

}
