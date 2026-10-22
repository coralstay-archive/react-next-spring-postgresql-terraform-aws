package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

/**
 * TASK-3#6 첫 ADMIN 은 app.admin.email(env ADMIN_EMAIL)로 지정한 LOCAL 계정이다. TASK-21#3(doc-2 BE-14): 가입 시 자동 승격은 없고 기동 시
 * 이미 있는 계정만 올린다.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
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

	/** 이 테스트가 잠시 USER 로 내린 기존 ADMIN (다른 테스트 클래스가 만든 계정). 끝나면 되돌린다. */
	List<Long> demotedAdmins = List.of();

	@BeforeEach
	void setUp() {
		api = new Api(port);
		auth = new AuthClient(api);
		// TASK-21#3 부트스트랩은 ADMIN 이 하나도 없을 때만 올린다. 컨텍스트(DB)를 같이 쓰는 다른 테스트의 ADMIN 을 잠시 내린다
		demotedAdmins = jdbc.queryForList("select id from users where role = 'ADMIN'", Long.class);
		jdbc.update("update users set role = 'USER' where role = 'ADMIN'");
	}

	@AfterEach
	void restoreAdmins() {
		for (Long id : demotedAdmins) {
			jdbc.update("update users set role = 'ADMIN' where id = ?", id);
		}
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
	@DisplayName("TASK-21#3 TASK-21#B2 설정된 이메일(대소문자 무시)로 가입해도 USER 다 — 가입만으로 ADMIN 이 되지 않고, 기동 시 승격돼야 ADMIN 이다")
	void signupWithAdminEmailIsNotAdminUntilStartup(CapturedOutput output) throws Exception {
		String email = IntegrationTest.ADMIN_EMAIL.toUpperCase();

		HttpResponse<String> response = auth.signup(email, AuthClient.PASSWORD, "admin");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		assertThat(AuthClient.json(response).get("role").asString()).isEqualTo("USER");
		assertThat(api.get("/test/admin-only")
			.bearer(auth.tokens(IntegrationTest.ADMIN_EMAIL).get("accessToken").asString())
			.send()
			.statusCode()).as("가입 직후 ADMIN API 403").isEqualTo(403);

		// 운영 절차: 운영자가 계정 소유를 확인한 뒤 재시작(재배포) → 기동 시 승격
		bootstrap.run(new DefaultApplicationArguments());

		Long userId = identities.findWithUserByProviderAndSubject(AuthProvider.LOCAL, IntegrationTest.ADMIN_EMAIL.toLowerCase())
			.orElseThrow()
			.getUser()
			.getId();
		assertThat(role(IntegrationTest.ADMIN_EMAIL.toLowerCase())).isEqualTo("ADMIN");
		assertThat(output).contains("user=" + userId + ")을 ADMIN 으로 올렸다");
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

	@Test
	@DisplayName("TASK-21#3 TASK-21#B2 ADMIN 이 이미 있으면 ADMIN_EMAIL 의 LOCAL 계정이 있어도 올리지 않는다(1회성 부트스트랩, 재시작마다 다시 올리지 않음)")
	void skipsWhenAnAdminAlreadyExists(CapturedOutput output) throws Exception {
		fixtures.admin();
		String email = auth.signedUp();

		runAtStartup(email);

		assertThat(role(email)).isEqualTo("USER");
		assertThat(output).contains("ADMIN 이 이미 있어");
	}

	@Test
	@DisplayName("TASK-21#3 TASK-21#B2 승격 뒤 그 LOCAL identity 가 없어지고 다른 사람이 같은 이메일로 가입해도, 재시작으로 다시 승격되지 않는다")
	void resquattedEmailIsNotPromotedAgain() throws Exception {
		String email = auth.signedUp();
		runAtStartup(email);
		assertThat(role(email)).isEqualTo("ADMIN");
		// 첫 관리자가 LOCAL 로그인을 해제(identity 삭제, 자격 증명은 cascade) → 이메일이 다시 비었다
		jdbc.update("delete from user_identities where provider = 'LOCAL' and subject = ?", email);

		assertThat(auth.signup(email, AuthClient.PASSWORD, "squatter").statusCode()).isEqualTo(201);
		runAtStartup(email);

		assertThat(role(email)).isEqualTo("USER");
	}

}
