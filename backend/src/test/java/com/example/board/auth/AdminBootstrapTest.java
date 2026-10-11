package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.auth.local.AdminBootstrap;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

import tools.jackson.databind.JsonNode;

/** TASK-3#6 첫 ADMIN 은 app.admin.email(env ADMIN_EMAIL)로 지정한 LOCAL 계정이다. */
@IntegrationTest
class AdminBootstrapTest {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	UserRepository users;

	@Autowired
	UserIdentityRepository identities;

	@Autowired
	AdminBootstrap bootstrap;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	AuthClient auth;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
	}

	private String role(String email) {
		return jdbc.queryForObject("""
				select u.role from users u join user_identities i on i.user_id = u.id
				where i.provider = 'LOCAL' and i.subject = ?""", String.class, email);
	}

	private void runAtStartup(String adminEmail) throws Exception {
		new AdminBootstrap(identities, users, adminEmail).run(new DefaultApplicationArguments());
	}

	@Test
	@DisplayName("TASK-3#6 설정된 이메일(대소문자 무시)로 LOCAL 가입하면 ADMIN 이 되고 ADMIN 전용 API 를 쓸 수 있다")
	void signupWithAdminEmailBecomesAdmin() {
		String email = IntegrationTest.ADMIN_EMAIL.toUpperCase();

		HttpResponse<String> response = auth.signup(email, AuthClient.PASSWORD, "admin");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		assertThat(AuthClient.json(response).get("role").asString()).isEqualTo("ADMIN");
		JsonNode tokens = auth.tokens(IntegrationTest.ADMIN_EMAIL);
		assertThat(api.get("/test/admin-only").bearer(tokens.get("accessToken").asString()).send().statusCode())
			.isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-3#6 다른 이메일로 가입하면 USER 다")
	void otherEmailIsUser() {
		String email = auth.signedUp();

		assertThat(role(email)).isEqualTo("USER");
		assertThat(api.get("/test/admin-only").bearer(auth.tokens(email).get("accessToken").asString()).send()
			.statusCode())
			.isEqualTo(403);
	}

	@Test
	@DisplayName("TASK-3#6 기동 시 이미 있는 그 이메일의 LOCAL 계정을 ADMIN 으로 올린다 (ApplicationRunner)")
	void promotesExistingLocalAccountAtStartup() throws Exception {
		assertThat(bootstrap).isInstanceOf(ApplicationRunner.class);
		String email = auth.signedUp();
		assertThat(role(email)).isEqualTo("USER");

		runAtStartup("  " + email.toUpperCase() + " ");

		assertThat(role(email)).isEqualTo("ADMIN");
		runAtStartup(email);
		assertThat(role(email)).as("멱등").isEqualTo("ADMIN");
	}

	@Test
	@DisplayName("TASK-3#6 LOCAL 이 아닌 identity(소셜)의 이메일이 같아도 ADMIN 으로 올리지 않는다")
	void ignoresNonLocalIdentity() throws Exception {
		User social = fixtures.user();
		String email = AuthClient.uniqueEmail();
		identities.saveAndFlush(UserIdentity.link(social, AuthProvider.GOOGLE, "google-" + social.getId(), email));

		runAtStartup(email);

		assertThat(users.findById(social.getId()).orElseThrow().getRole().name()).isEqualTo("USER");
	}

	@Test
	@DisplayName("TASK-3#6 설정이 비어 있으면 아무것도 하지 않는다")
	void blankConfigIsNoop() throws Exception {
		String email = auth.signedUp();

		runAtStartup("");
		runAtStartup(null);

		assertThat(role(email)).isEqualTo("USER");
	}

}
