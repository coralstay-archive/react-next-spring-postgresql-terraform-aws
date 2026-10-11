package com.example.board.auth.oauth;

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
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.OAuthFlow;
import com.example.board.support.OAuthFlow.Authorization;
import com.example.board.support.OAuthProviderStub;
import com.example.board.support.OAuthProviderStub.KakaoUser;
import com.example.board.support.OAuthProviderStub.NaverUser;
import com.example.board.user.User;

/**
 * TASK-5#3 Naver·Kakao 로그인 전체 흐름(시작 → state → 콜백 → 세션 → users·user_identities 행)을 WireMock 제공자로 HTTP 로 탄다.
 * 계정 연결과 제공자 오류도 같은 흐름으로 확인한다.
 */
@IntegrationTest
class NaverKakaoLoginTest {

	static final String LOGIN_OK = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?status=ok";

	static final String LOGIN_ERROR = OAuthFlow.PUBLIC_BASE_URL + "/auth/callback/?error=";

	static final String ACCOUNT = OAuthFlow.PUBLIC_BASE_URL + "/account/";

	@LocalServerPort
	int port;

	@Autowired
	OAuthProviderStub stub;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	Api api;

	OAuthFlow flow;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		flow = new OAuthFlow(api, stub);
	}

	static String naverId() {
		return "nv" + UUID.randomUUID().toString().replace("-", "");
	}

	static long kakaoId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
	}

	private Long userIdOf(String provider, String subject) {
		return jdbc.query("select user_id from user_identities where provider = ? and subject = ?",
				rs -> rs.next() ? rs.getLong(1) : null, provider, subject);
	}

	private Map<String, Object> user(Long id) {
		return jdbc.queryForMap("select nickname, email, role, status from users where id = ?", id);
	}

	private String identityEmail(String provider, String subject) {
		return jdbc.queryForObject("select email from user_identities where provider = ? and subject = ?", String.class,
				provider, subject);
	}

	private String whoami(String session) {
		return api.get("/test/whoami").cookie(session).send().body();
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#1 Naver 인가 시작: 302 → Naver 인가 엔드포인트, client_id·공개 주소 redirect_uri·state, scope 없음")
	void naverAuthorizationRedirect() {
		Authorization authorization = flow.authorize("/oauth2/authorization/naver");

		assertThat(authorization.location().toString()).startsWith(stub.url("/naver/authorize?"));
		assertThat(authorization.param("response_type")).isEqualTo("code");
		assertThat(authorization.param("client_id")).isEqualTo(OAuthProviderStub.NAVER_CLIENT_ID);
		assertThat(authorization.param("redirect_uri"))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/login/oauth2/code/naver");
		assertThat(authorization.param("state")).isNotBlank();
		assertThat(authorization.params()).doesNotContainKey("scope");
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#2 Kakao 인가 시작: 302 → Kakao 인가 엔드포인트, scope 는 쉼표 구분(profile_nickname,account_email)")
	void kakaoAuthorizationRedirect() {
		Authorization authorization = flow.authorize("/oauth2/authorization/kakao");

		assertThat(authorization.location().toString()).startsWith(stub.url("/kakao/authorize?"));
		assertThat(authorization.param("client_id")).isEqualTo(OAuthProviderStub.KAKAO_CLIENT_ID);
		assertThat(authorization.param("scope")).isEqualTo("profile_nickname,account_email");
		assertThat(authorization.param("redirect_uri"))
			.isEqualTo(OAuthFlow.PUBLIC_BASE_URL + "/api/login/oauth2/code/kakao");
		assertThat(authorization.param("state")).isNotBlank();
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#1 Naver 로그인: client_secret_post 토큰 교환(expires_in 문자열) → response 래핑 프로필 → 새 계정(닉네임·이메일) + 세션")
	void naverLogin() {
		String id = naverId();

		HttpResponse<String> callback = flow.naver(new NaverUser(id, "Naver.User@Naver.com", "네이버유저", "김네이버"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		Long userId = userIdOf("NAVER", id);
		assertThat(userId).isNotNull();
		assertThat(user(userId)).containsEntry("nickname", "네이버유저")
			.containsEntry("email", "naver.user@naver.com")
			.containsEntry("role", "USER")
			.containsEntry("status", "ACTIVE");
		assertThat(identityEmail("NAVER", id)).isEqualTo("naver.user@naver.com");
		assertThat(whoami(flow.session())).contains("\"id\":" + userId);
		assertThat(api.get("/auth/me").cookie(flow.session()).send().body()).contains("\"providers\":[\"NAVER\"]");
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#1 Naver 재로그인은 같은 계정, 이메일·별명 미동의면 이름으로 닉네임")
	void naverRelogin() {
		String id = naverId();
		assertThat(OAuthFlow.location(flow.naver(new NaverUser(id, null, null, "홍길동")))).isEqualTo(LOGIN_OK);
		Long userId = userIdOf("NAVER", id);
		assertThat(user(userId)).containsEntry("nickname", "홍길동").containsEntry("email", null);
		int users = count("select count(*) from users");

		OAuthFlow again = new OAuthFlow(api, stub);
		assertThat(OAuthFlow.location(again.naver(new NaverUser(id, null, "새별명", null)))).isEqualTo(LOGIN_OK);

		assertThat(count("select count(*) from users")).isEqualTo(users);
		assertThat(whoami(again.session())).contains("\"id\":" + userId);
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#2 Kakao 로그인: 숫자 id 가 subject, 인증된 이메일은 users.email 에, 닉네임은 profile.nickname")
	void kakaoLogin() {
		long id = kakaoId();

		HttpResponse<String> callback = flow.kakao(new KakaoUser(id, "Kakao.User@Kakao.com", true, "카카오유저"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		Long userId = userIdOf("KAKAO", Long.toString(id));
		assertThat(userId).isNotNull();
		assertThat(user(userId)).containsEntry("nickname", "카카오유저").containsEntry("email", "kakao.user@kakao.com");
		assertThat(whoami(flow.session())).contains("\"id\":" + userId);
		assertThat(api.get("/auth/me").cookie(flow.session()).send().body()).contains("\"providers\":[\"KAKAO\"]");
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#2 TASK-5#B1 Kakao 이메일 미동의: 로그인 성공, users.email·identity.email 모두 null")
	void kakaoLoginWithoutEmail() {
		long id = kakaoId();

		HttpResponse<String> callback = flow.kakao(new KakaoUser(id, null, false, "이메일미동의"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_OK);
		Long userId = userIdOf("KAKAO", Long.toString(id));
		assertThat(user(userId)).containsEntry("nickname", "이메일미동의").containsEntry("email", null);
		assertThat(identityEmail("KAKAO", Long.toString(id))).isNull();
		assertThat(whoami(flow.session())).contains("\"id\":" + userId);
	}

	@Test
	@DisplayName("TASK-5#3 TASK-5#2 Kakao 이메일이 인증되지 않았으면(is_email_verified=false) 기록하지 않는다")
	void kakaoUnverifiedEmail() {
		long id = kakaoId();

		assertThat(OAuthFlow.location(flow.kakao(new KakaoUser(id, "unverified@kakao.com", false, "미인증"))))
			.isEqualTo(LOGIN_OK);

		assertThat(user(userIdOf("KAKAO", Long.toString(id)))).containsEntry("email", null);
		assertThat(identityEmail("KAKAO", Long.toString(id))).isNull();
	}

	@Test
	@DisplayName("TASK-5#3 Naver·Kakao 계정 연결: LOCAL 계정에 둘 다 연결(?linked=naver|kakao), 이후 각 수단으로 로그인하면 같은 계정")
	void linkBoth() {
		User me = fixtures.user();
		OAuthFlow linking = new OAuthFlow(api, stub).session(fixtures.sessionCookie(me));
		String naverId = naverId();
		long kakaoId = kakaoId();

		assertThat(OAuthFlow.location(linking.naver("/me/identities/link/naver", new NaverUser(naverId, null, "n", null))))
			.isEqualTo(ACCOUNT + "?linked=naver");
		assertThat(OAuthFlow.location(linking.kakao("/me/identities/link/kakao", new KakaoUser(kakaoId, null, false, "k"))))
			.isEqualTo(ACCOUNT + "?linked=kakao");

		assertThat(userIdOf("NAVER", naverId)).isEqualTo(me.getId());
		assertThat(userIdOf("KAKAO", Long.toString(kakaoId))).isEqualTo(me.getId());
		OAuthFlow naverLogin = new OAuthFlow(api, stub);
		naverLogin.naver(new NaverUser(naverId, null, "n", null));
		assertThat(whoami(naverLogin.session())).contains("\"id\":" + me.getId());
		OAuthFlow kakaoLogin = new OAuthFlow(api, stub);
		kakaoLogin.kakao(new KakaoUser(kakaoId, null, false, "k"));
		assertThat(whoami(kakaoLogin.session())).contains("\"id\":" + me.getId());
	}

	@Test
	@DisplayName("TASK-5#3 다른 계정에 연결된 Kakao identity 는 연결할 수 없다(?error=identity_in_use)")
	void kakaoIdentityInUse() {
		long id = kakaoId();
		flow.kakao(new KakaoUser(id, null, false, "owner"));
		Long owner = userIdOf("KAKAO", Long.toString(id));
		User me = fixtures.user();

		HttpResponse<String> callback = new OAuthFlow(api, stub).session(fixtures.sessionCookie(me))
			.kakao("/me/identities/link/kakao", new KakaoUser(id, null, false, "owner"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(ACCOUNT + "?error=identity_in_use");
		assertThat(userIdOf("KAKAO", Long.toString(id))).isEqualTo(owner);
	}

	@Test
	@DisplayName("TASK-5#3 Naver 토큰 오류(HTTP 200 + error 본문, access_token 없음)는 ?error=invalid_token_response, 계정을 만들지 않는다")
	void naverTokenError() {
		Authorization authorization = flow.authorize("/oauth2/authorization/naver");
		String code = OAuthFlow.newCode();
		stub.stubNaverTokenError(code, "invalid_request");
		int users = count("select count(*) from users");

		HttpResponse<String> callback = flow.callback("naver", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_token_response");
		assertThat(stub.tokenRequests("naver", code)).isEqualTo(1);
		assertThat(count("select count(*) from users")).isEqualTo(users);
		assertThat(api.get("/test/whoami").cookie(flow.session()).send().statusCode()).isEqualTo(401);
	}

	@Test
	@DisplayName("TASK-5#3 Kakao 사용자 정보가 401 이면 ?error=invalid_user_info_response, 계정을 만들지 않는다")
	void kakaoUserInfoFailure() {
		Authorization authorization = flow.authorize("/oauth2/authorization/kakao");
		String code = OAuthFlow.newCode();
		stub.stubKakaoUserFailure(code, authorization.param("redirect_uri"));
		int users = count("select count(*) from users");

		HttpResponse<String> callback = flow.callback("kakao", "code=" + code + "&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "invalid_user_info_response");
		assertThat(count("select count(*) from users")).isEqualTo(users);
	}

	@Test
	@DisplayName("TASK-5#3 Naver 사용자가 거부하면(error=access_denied) ?error=access_denied")
	void naverDenied() {
		Authorization authorization = flow.authorize("/oauth2/authorization/naver");

		HttpResponse<String> callback = flow.callback("naver",
				"error=access_denied&error_description=Canceled+By+User&state=" + authorization.param("state"));

		assertThat(OAuthFlow.location(callback)).isEqualTo(LOGIN_ERROR + "access_denied");
	}

}
