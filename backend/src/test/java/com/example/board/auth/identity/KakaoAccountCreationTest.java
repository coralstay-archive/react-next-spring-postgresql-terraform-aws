package com.example.board.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.example.board.auth.oauth.KakaoUserInfoMapper;
import com.example.board.support.IntegrationTest;
import com.example.board.user.User;

/** TASK-5#2 TASK-5#B1 Kakao 이메일 미동의 사용자도 계정을 만들 수 있다(users.email·identity.email 모두 null). */
@IntegrationTest
class KakaoAccountCreationTest {

	@Autowired
	KakaoUserInfoMapper mapper;

	@Autowired
	IdentityResolver resolver;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@DisplayName("TASK-5#2 TASK-5#B1 이메일 동의 없이(kakao_account 에 email 없음) 들어온 Kakao 사용자는 email null 로 새 계정이 생긴다")
	void createsAccountWithoutEmail() {
		long id = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("id", id);
		attributes.put("kakao_account", Map.of("email_needs_agreement", true, "profile",
				Map.of("nickname", "이메일없음")));

		User user = resolver.resolveForLogin(mapper
			.map(new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id")));

		Map<String, Object> row = jdbc.queryForMap("select nickname, email from users where id = ?", user.getId());
		assertThat(row).containsEntry("nickname", "이메일없음").containsEntry("email", null);
		assertThat(jdbc.queryForMap("select user_id, email from user_identities where provider = 'KAKAO' and subject = ?",
				Long.toString(id)))
			.containsEntry("user_id", user.getId())
			.containsEntry("email", null);
	}

}
