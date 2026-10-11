package com.example.board.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GitHubUser;
import com.example.board.support.OAuthProviderStub.GoogleUser;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;

/**
 * TASK-4#3 로그인 상태에서 {@code GET /api/me/identities/link/{provider}} 로 시작하면 콜백에서 현재 계정에 identity 를 붙인다
 * (decision-8: 연결은 로그인 상태에서 명시적으로만).
 */
@IntegrationTest
class IdentityLinkTest {

	static final String ACCOUNT = OAuthFlow.PUBLIC_BASE_URL + "/account/";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	UserIdentityRepository identities;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private OAuthFlow flow(String session) {
		return new OAuthFlow(api, stub).session(session);
	}

	private static String sub() {
		return "g-" + UUID.randomUUID();
	}

	private static long githubId() {
		return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
	}

	private Long ownerOf(AuthProvider provider, String subject) {
		return jdbc.query("select user_id from user_identities where provider = ? and subject = ?",
				rs -> rs.next() ? rs.getLong(1) : null, provider.name(), subject);
	}

	private String whoami(String session) {
		return api.get("/test/whoami").cookie(session).send().body();
	}

	@Test
	@DisplayName("TASK-4#3 연결 시작: 302 → 공개 주소의 인가 시작(?link=true), 콜백 뒤 현재 계정에 identity 가 붙고 /account/?linked=google 로 302")
	void linkGoogleToLocalAccount() {
		String email = auth.signedUp();
		String session = auth.sessionCookie(email);
		Long me = jdbc.queryForObject("select user_id from user_identities where provider = 'LOCAL' and subject = ?",
				Long.class, email);
		OAuthFlow flow = flow(session);
		int users = jdbc.queryForObject("select count(*) from users", Integer.class);

		HttpResponse<String> start = flow.get("/me/identities/link/google");
		assertThat(OAuthFlow.location(start))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/oauth2/authorization/google?link=true");

		String sub = sub();
		HttpResponse<String> callback = flow.google("/me/identities/link/google",
				new GoogleUser(sub, "other@gmail.test", true, "Linked"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?linked=google");
		assertThat(ownerOf(AuthProvider.GOOGLE, sub)).isEqualTo(me);
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(users);
		assertThat(whoami(flow.session())).as("연결 후에도 같은 계정으로 로그인돼 있다").contains("\"id\":" + me);
		assertThat(api.get("/auth/me").cookie(flow.session()).send().body())
			.contains("\"providers\":[\"LOCAL\",\"GOOGLE\"]");
	}

	@Test
	@DisplayName("TASK-4#3 연결한 identity 로 로그인하면 원래 계정으로 들어온다")
	void linkedIdentityLogsIntoSameAccount() {
		User me = fixtures.user();
		long id = githubId();
		HttpResponse<String> linked = flow(fixtures.sessionCookie(me)).github("/me/identities/link/github",
				new GitHubUser(id, "linker", "Linker", null));
		assertThat(OAuthFlow.location(linked)).isEqualTo(ACCOUNT + "?linked=github");

		OAuthFlow login = flow(null);
		login.github(new GitHubUser(id, "linker", "Linker", null));

		assertThat(whoami(login.session())).contains("\"id\":" + me.getId());
	}

	@Test
	@DisplayName("TASK-4#3 연결 시작은 인증이 필요하다: 익명은 401 JSON, 리다이렉트 없음")
	void requiresAuthentication() {
		HttpResponse<String> response = api.get("/me/identities/link/google").send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Location")).isEmpty();
	}

	@Test
	@DisplayName("TASK-4#3 연결은 브라우저 세션 흐름이다: Bearer 요청은 400")
	void bearerCannotLink() {
		HttpResponse<String> response = api.get("/me/identities/link/google").bearer(fixtures.bearer(fixtures.user()))
			.send();

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("TASK-4#3 설정되지 않은 제공자로 연결을 시작하면 404 unknown_provider")
	void unknownProvider() {
		HttpResponse<String> response = api.get("/me/identities/link/facebook")
			.cookie(fixtures.sessionCookie(fixtures.user()))
			.send();

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("\"error\":\"unknown_provider\"");
	}

	@Test
	@DisplayName("TASK-4#3 TASK-4#B2 다른 계정에 연결된 identity 는 연결할 수 없다: /account/?error=identity_in_use, 아무것도 바뀌지 않는다")
	void identityInUse() {
		User owner = fixtures.user();
		String sub = sub();
		identities.saveAndFlush(UserIdentity.link(owner, AuthProvider.GOOGLE, sub, null));
		User me = fixtures.user();
		OAuthFlow flow = flow(fixtures.sessionCookie(me));

		HttpResponse<String> callback = flow.google("/me/identities/link/google", new GoogleUser(sub, null, false, "Taken"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=identity_in_use");
		assertThat(ownerOf(AuthProvider.GOOGLE, sub)).isEqualTo(owner.getId());
		assertThat(jdbc.queryForObject("select count(*) from user_identities where user_id = ?", Integer.class,
				me.getId())).isZero();
		assertThat(whoami(flow.session())).as("다른 계정으로 로그인되지 않는다").contains("\"id\":" + me.getId());
	}

	@Test
	@DisplayName("TASK-4#3 이미 내 계정에 붙은 identity 를 다시 연결하면 그대로 성공(?linked=)")
	void alreadyLinkedToMe() {
		User me = fixtures.user();
		String sub = sub();
		identities.saveAndFlush(UserIdentity.link(me, AuthProvider.GOOGLE, sub, null));

		HttpResponse<String> callback = flow(fixtures.sessionCookie(me)).google("/me/identities/link/google",
				new GoogleUser(sub, null, false, "Mine"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?linked=google");
		assertThat(jdbc.queryForObject("select count(*) from user_identities where user_id = ?", Integer.class,
				me.getId())).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-4#3 연결 플래그는 시작한 사용자에게만 유효하다: 그 사이 세션이 다른 계정으로 바뀌면 ?error=link_expired, 연결하지 않는다")
	void intentBoundToUser() {
		User alice = fixtures.user();
		OAuthFlow flow = flow(fixtures.sessionCookie(alice));
		Authorization authorization = flow.authorize("/me/identities/link/google");
		// 같은 브라우저 세션이 다른 계정(bob)으로 로그인한다 (세션 ID 는 바뀌지만 세션 속성은 유지된다)
		String bob = auth.signedUp();
		HttpResponse<String> login = api.post("/auth/login")
			.cookie(flow.session())
			.csrf(auth.csrfToken())
			.json(AuthClient.loginBody(bob, AuthClient.PASSWORD))
			.send();
		assertThat(login.statusCode()).isEqualTo(200);
		flow.session("SESSION=" + Api.cookie(login, "SESSION").orElseThrow());
		String sub = sub();
		String code = OAuthFlow.newCode();
		stub.stubGoogle(code, authorization.param("redirect_uri"), authorization.param("nonce"),
				new GoogleUser(sub, null, false, "Stale"));

		HttpResponse<String> callback = flow.callback("google", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=link_expired");
		assertThat(ownerOf(AuthProvider.GOOGLE, sub)).isNull();
	}

	@Test
	@DisplayName("TASK-4#3 연결을 시작했다가 일반 로그인으로 다시 시작하면 연결 플래그는 버려지고 일반 로그인이 된다")
	void plainLoginClearsIntent() {
		User me = fixtures.user();
		OAuthFlow flow = flow(fixtures.sessionCookie(me));
		flow.authorize("/me/identities/link/github");
		long id = githubId();

		HttpResponse<String> callback = flow.github(new GitHubUser(id, "someone", "Someone", null));

		assertThat(OAuthFlow.location(callback)).isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok");
		assertThat(ownerOf(AuthProvider.GITHUB, Long.toString(id))).isNotEqualTo(me.getId()).isNotNull();
	}

	@Test
	@DisplayName("TASK-4#3 연결 중 제공자가 거부하면 /account/?error=access_denied")
	void deniedWhileLinking() {
		OAuthFlow flow = flow(fixtures.sessionCookie(fixtures.user()));
		Authorization authorization = flow.authorize("/me/identities/link/google");

		HttpResponse<String> callback = flow.callback("google",
				"error=access_denied&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=access_denied");
	}

	@Test
	@DisplayName("TASK-4#3 SUSPENDED 계정은 연결할 수 없다: /account/?error=suspended")
	void suspendedCannotLink() {
		User suspended = fixtures.suspended();
		String sub = sub();

		HttpResponse<String> callback = flow(fixtures.sessionCookie(suspended)).google("/me/identities/link/google",
				new GoogleUser(sub, null, false, "Suspended"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=suspended");
		assertThat(ownerOf(AuthProvider.GOOGLE, sub)).isNull();
	}

}
