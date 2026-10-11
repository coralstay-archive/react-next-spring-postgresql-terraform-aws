package com.example.board.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
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
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.GitHubUser;
import com.example.board.support.OAuthProviderStub.GoogleUser;

/** TASK-4#2 IdentityResolver: (provider, subject) 가 있으면 그 계정으로 로그인, 없으면 새 계정. 실제 OAuth 흐름으로 확인한다. */
@IntegrationTest
class IdentityResolverTest {

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private OAuthFlow flow() {
		return new OAuthFlow(api, stub);
	}

	private static String sub() {
		return "g-" + UUID.randomUUID();
	}

	private Long userIdOf(String provider, String subject) {
		return jdbc.queryForObject("select user_id from user_identities where provider = ? and subject = ?", Long.class,
				provider, subject);
	}

	private Map<String, Object> user(Long id) {
		return jdbc.queryForMap("select nickname, email, role, status from users where id = ?", id);
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private static void assertLoggedIn(HttpResponse<String> callback) {
		assertThat(OAuthFlow.location(callback)).isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok");
	}

	@Test
	@DisplayName("TASK-4#2 처음 보는 (GOOGLE, sub): 새 계정 + identity, 닉네임은 이름, 검증된 이메일은 users.email 과 identity 에")
	void createsAccount() {
		String sub = sub();

		assertLoggedIn(flow().google(new GoogleUser(sub, "New.User@Gmail.Test", true, "  New   User ")));

		Long userId = userIdOf("GOOGLE", sub);
		assertThat(user(userId)).containsEntry("nickname", "New User")
			.containsEntry("email", "new.user@gmail.test")
			.containsEntry("role", "USER")
			.containsEntry("status", "ACTIVE");
		assertThat(jdbc.queryForObject("select email from user_identities where provider = 'GOOGLE' and subject = ?",
				String.class, sub)).isEqualTo("new.user@gmail.test");
		assertThat(count("select count(*) from user_identities where user_id = ?", userId)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-4#2 검증되지 않은 이메일은 identity 에만 기록하고 users.email 은 비워 둔다")
	void unverifiedEmailNotOnUser() {
		String sub = sub();

		assertLoggedIn(flow().google(new GoogleUser(sub, "unverified@x.test", false, "Unverified")));

		Long userId = userIdOf("GOOGLE", sub);
		assertThat(user(userId).get("email")).isNull();
		assertThat(jdbc.queryForObject("select email from user_identities where user_id = ?", String.class, userId))
			.isEqualTo("unverified@x.test");
	}

	@Test
	@DisplayName("TASK-4#2 이미 있는 (provider, subject) 로 다시 로그인하면 같은 계정, 계정·identity 를 더 만들지 않는다")
	void existingIdentityLogsIn() {
		long id = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
		OAuthFlow first = flow();
		assertLoggedIn(first.github(new GitHubUser(id, "octo", "Octo Cat", null)));
		Long userId = userIdOf("GITHUB", Long.toString(id));
		int users = count("select count(*) from users");

		OAuthFlow second = flow();
		assertLoggedIn(second.github(new GitHubUser(id, "octo-renamed", "Renamed", "octo@github.test")));

		assertThat(count("select count(*) from users")).isEqualTo(users);
		assertThat(count("select count(*) from user_identities where user_id = ?", userId)).isEqualTo(1);
		assertThat(api.get("/test/whoami").cookie(second.session()).send().body()).contains("\"id\":" + userId);
		assertThat(user(userId)).as("기존 계정 정보는 로그인으로 바뀌지 않는다").containsEntry("nickname", "Octo Cat");
	}

	@Test
	@DisplayName("TASK-4#2 닉네임 규칙(2~20자): GitHub 이름이 없으면 login, 긴 이름은 20자로 자르고, 너무 짧으면 userNNNN")
	void nicknames() {
		long a = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
		long b = a + 1;
		long c = a + 2;

		flow().github(new GitHubUser(a, "mona-login", null, null));
		flow().github(new GitHubUser(b, "long", "An Extremely Long Display Name Indeed", null));
		flow().github(new GitHubUser(c, "x", "\u0001", null));

		assertThat(user(userIdOf("GITHUB", Long.toString(a))).get("nickname")).isEqualTo("mona-login");
		assertThat(user(userIdOf("GITHUB", Long.toString(b))).get("nickname")).isEqualTo("An Extremely Long Di");
		assertThat((String) user(userIdOf("GITHUB", Long.toString(c))).get("nickname")).matches("user\\d{4}");
	}

	@Test
	@DisplayName("TASK-4#2 TASK-4#B1 같은 이메일의 LOCAL 계정이 있어도 Google 로그인은 별도의 새 계정을 만든다(자동 병합 금지)")
	void neverMergesByEmailWithLocal() {
		String email = AuthClient.uniqueEmail();
		auth.signedUp(email);
		Long localUser = jdbc.queryForObject(
				"select user_id from user_identities where provider = 'LOCAL' and subject = ?", Long.class, email);
		String sub = sub();

		OAuthFlow google = flow();
		assertLoggedIn(google.google(new GoogleUser(sub, email, true, "Same Email")));

		Long googleUser = userIdOf("GOOGLE", sub);
		assertThat(googleUser).isNotEqualTo(localUser);
		assertThat(count("select count(*) from user_identities where user_id = ?", localUser)).isEqualTo(1);
		assertThat(api.get("/test/whoami").cookie(google.session()).send().body()).contains("\"id\":" + googleUser);
		assertThat(api.get("/auth/me").cookie(google.session()).send().body()).contains("\"providers\":[\"GOOGLE\"]");
	}

	@Test
	@DisplayName("TASK-4#2 TASK-4#B1 Google 과 GitHub 의 이메일이 같아도 서로 다른 계정이다")
	void neverMergesAcrossProviders() {
		String email = AuthClient.uniqueEmail();
		String sub = sub();
		long id = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);

		flow().google(new GoogleUser(sub, email, true, "Google"));
		flow().github(new GitHubUser(id, "gh", "GitHub", email));

		assertThat(userIdOf("GOOGLE", sub)).isNotEqualTo(userIdOf("GITHUB", Long.toString(id)));
	}

}
