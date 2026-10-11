package com.example.board.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

import tools.jackson.databind.JsonNode;

/** TASK-12#1 관리자 사용자 목록·정지·해제. */
@IntegrationTest
class AdminUserApiTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserRepository users;

	@Autowired
	UserIdentityRepository identities;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	User admin;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		admin = fixtures.admin();
		adminBearer = fixtures.bearer(admin);
	}

	private User account(String nickname, String email, String phone, AuthProvider... providers) {
		User user = User.create(nickname);
		user.changeEmail(email);
		user.changePhone(phone);
		user = users.saveAndFlush(user);
		for (AuthProvider provider : providers) {
			identities.saveAndFlush(UserIdentity.link(user, provider, BoardFixtures.unique("sub-"), email));
		}
		return user;
	}

	private JsonNode list(String query) {
		HttpResponse<String> response = api.get("/admin/users" + query).bearer(adminBearer).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	private static List<Long> ids(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach(item -> ids.add(item.get("id").asLong()));
		return ids;
	}

	private HttpResponse<String> suspend(long userId, String reason) {
		Api.Request request = api.post("/admin/users/" + userId + "/suspend").bearer(adminBearer);
		if (reason != null) {
			request.json("{\"reason\":" + AuthClient.quote(reason) + "}");
		}
		return request.send();
	}

	private List<Map<String, Object>> auditRows(String action, long targetId) {
		return jdbc.queryForList(
				"select admin_id, action, target_type, target_id, reason from admin_actions where action = ? and target_id = ? order by id",
				action, targetId);
	}

	@Test
	@DisplayName("TASK-12#1 GET /admin/users: 닉네임·이메일·전화 부분 검색(대소문자 무시), 로그인 수단 목록, 페이지 모양")
	void listSearchesAndIncludesProviders() {
		String tag = BoardFixtures.unique("nk");
		User alice = account("Alice-" + tag, "alice-" + tag + "@Example.com", "+821011112222", AuthProvider.LOCAL,
				AuthProvider.KAKAO);
		User bob = account("bob-" + tag, "bob-" + tag + "@example.com", null, AuthProvider.GOOGLE);

		JsonNode byNickname = list("?q=" + tag.toUpperCase());
		assertThat(ids(byNickname)).containsExactly(bob.getId(), alice.getId());
		assertThat(byNickname.get("totalElements").asLong()).isEqualTo(2);
		assertThat(byNickname.get("page").asInt()).isZero();
		assertThat(byNickname.get("size").asInt()).isEqualTo(20);

		JsonNode first = byNickname.get("items").get(1);
		assertThat(first.get("nickname").asString()).isEqualTo("Alice-" + tag);
		assertThat(first.get("email").asString()).isEqualTo("alice-" + tag + "@Example.com");
		assertThat(first.get("phone").asString()).isEqualTo("+821011112222");
		assertThat(first.get("role").asString()).isEqualTo("USER");
		assertThat(first.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(first.get("createdAt").isNull()).isFalse();
		List<String> providers = new ArrayList<>();
		first.get("providers").forEach(p -> providers.add(p.asString()));
		assertThat(providers).containsExactlyInAnyOrder("LOCAL", "KAKAO");

		assertThat(ids(list("?q=alice-" + tag + "@example"))).containsExactly(alice.getId());

		// 전화번호: 저장된 E.164 의 부분 문자열, 또는 하이픈·앞자리 0 이 붙은 국내 표기
		String phoneTag = String.valueOf(System.nanoTime() % 100000000L);
		String phone = "+8210" + String.format("%08d", Long.parseLong(phoneTag));
		User carol = account(BoardFixtures.unique("carol"), null, phone, AuthProvider.PHONE);
		String local = "010" + phone.substring(5);
		assertThat(ids(list("?q=" + phone.substring(3)))).contains(carol.getId());
		assertThat(ids(list("?q=" + local.substring(0, 3) + "-" + local.substring(3, 7) + "-" + local.substring(7))))
			.contains(carol.getId());
	}

	@Test
	@DisplayName("TASK-12#1 GET /admin/users: status 필터, 페이지 크기, 잘못된 파라미터 400")
	void listFiltersByStatusAndPages() {
		String tag = BoardFixtures.unique("st");
		User active = account("a-" + tag, null, null);
		User suspended = account("s-" + tag, null, null);
		suspended.suspend();
		users.saveAndFlush(suspended);

		assertThat(ids(list("?q=" + tag + "&status=SUSPENDED"))).containsExactly(suspended.getId());
		assertThat(ids(list("?q=" + tag + "&status=ACTIVE"))).containsExactly(active.getId());

		JsonNode page = list("?q=" + tag + "&size=1&page=1");
		assertThat(ids(page)).containsExactly(active.getId());
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);

		assertThat(api.get("/admin/users?size=0").bearer(adminBearer).send().statusCode()).isEqualTo(400);
		assertThat(api.get("/admin/users?size=101").bearer(adminBearer).send().statusCode()).isEqualTo(400);
		assertThat(api.get("/admin/users?page=-1").bearer(adminBearer).send().statusCode()).isEqualTo(400);
		assertThat(api.get("/admin/users?status=NOPE").bearer(adminBearer).send().statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-12#1 정지는 즉시 효과: 기존 세션은 다음 요청부터 401(세션 삭제), refresh 는 거부, access 토큰 쓰기는 403, 감사 로그")
	void suspensionTakesEffectImmediately() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		String otherSession = auth.sessionCookie(email);
		JsonNode tokens = auth.tokens(email);
		long userId = AuthClient.json(api.get("/auth/me").cookie(session).send()).get("id").asLong();

		// 세션은 principal name(= 사용자 id)으로 색인돼 있어야 정지 시 찾아 지울 수 있다
		assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				String.valueOf(userId))).isEqualTo(2);

		// 다른 사용자의 세션은 영향받지 않는다
		String bystander = auth.sessionCookie(auth.signedUp());

		HttpResponse<String> response = suspend(userId, "스팸");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode body = AuthClient.json(response);
		assertThat(body.get("id").asLong()).isEqualTo(userId);
		assertThat(body.get("status").asString()).isEqualTo("SUSPENDED");

		assertThat(api.get("/auth/me").cookie(session).send().statusCode()).isEqualTo(401);
		assertThat(api.get("/auth/me").cookie(otherSession).send().statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
				String.valueOf(userId))).isZero();
		assertThat(api.get("/auth/me").cookie(bystander).send().statusCode()).isEqualTo(200);

		HttpResponse<String> refresh = auth.refresh(tokens.get("refreshToken").asString());
		assertThat(refresh.statusCode()).isIn(401, 403);
		assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
				Long.class, userId)).isZero();

		HttpResponse<String> write = api.post("/posts")
			.bearer(tokens.get("accessToken").asString())
			.json("{\"title\":\"t\",\"content\":\"c\"}")
			.send();
		assertThat(write.statusCode()).isEqualTo(403);

		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(403);

		List<Map<String, Object>> audit = auditRows("USER_SUSPEND", userId);
		assertThat(audit).hasSize(1);
		assertThat(audit.get(0)).containsEntry("admin_id", admin.getId())
			.containsEntry("target_type", "USER")
			.containsEntry("reason", "스팸");
	}

	@Test
	@DisplayName("TASK-12#1 해제하면 다시 로그인할 수 있다. 정지·해제는 멱등이고 매번 감사 로그를 남긴다")
	void unsuspendRestoresAccess() {
		String email = auth.signedUp();
		long userId = AuthClient.json(api.get("/auth/me").cookie(auth.sessionCookie(email)).send()).get("id").asLong();

		assertThat(suspend(userId, null).statusCode()).isEqualTo(200);
		assertThat(suspend(userId, null).statusCode()).isEqualTo(200);
		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(403);

		HttpResponse<String> response = api.post("/admin/users/" + userId + "/unsuspend").bearer(adminBearer).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuthClient.json(response).get("status").asString()).isEqualTo("ACTIVE");
		assertThat(api.post("/admin/users/" + userId + "/unsuspend").bearer(adminBearer).send().statusCode())
			.isEqualTo(200);

		assertThat(auth.sessionLogin(email, AuthClient.PASSWORD).statusCode()).isEqualTo(200);
		assertThat(auditRows("USER_SUSPEND", userId)).hasSize(2);
		assertThat(auditRows("USER_UNSUSPEND", userId)).hasSize(2).allSatisfy(row -> assertThat(row.get("reason")).isNull());
	}

	@Test
	@DisplayName("TASK-12#B2 자기 자신은 정지할 수 없다(409 cannot_suspend_self). 다른 ADMIN 도 정지할 수 없다(409 cannot_suspend_admin)")
	void cannotSuspendSelfOrAdmin() {
		HttpResponse<String> self = suspend(admin.getId(), "실수");
		assertThat(self.statusCode()).isEqualTo(409);
		assertThat(AuthClient.json(self).get("error").asString()).isEqualTo("cannot_suspend_self");

		User otherAdmin = fixtures.admin();
		HttpResponse<String> other = suspend(otherAdmin.getId(), null);
		assertThat(other.statusCode()).isEqualTo(409);
		assertThat(AuthClient.json(other).get("error").asString()).isEqualTo("cannot_suspend_admin");

		assertThat(users.findStatusById(admin.getId()).orElseThrow().name()).isEqualTo("ACTIVE");
		assertThat(users.findStatusById(otherAdmin.getId()).orElseThrow().name()).isEqualTo("ACTIVE");
		assertThat(auditRows("USER_SUSPEND", admin.getId())).isEmpty();
		assertThat(auditRows("USER_SUSPEND", otherAdmin.getId())).isEmpty();
	}

	@Test
	@DisplayName("TASK-12#1 없는 사용자는 404, 사유가 너무 길면 400")
	void notFoundAndValidation() {
		assertThat(suspend(Long.MAX_VALUE, null).statusCode()).isEqualTo(404);
		assertThat(api.post("/admin/users/" + Long.MAX_VALUE + "/unsuspend").bearer(adminBearer).send().statusCode())
			.isEqualTo(404);
		assertThat(suspend(fixtures.user().getId(), "x".repeat(501)).statusCode()).isEqualTo(400);
	}

}
