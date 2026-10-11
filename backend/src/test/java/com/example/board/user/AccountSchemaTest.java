package com.example.board.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.IntegrationTest;

/** TASK-2#1 통합 계정 스키마(users 1:N user_identities)와 JPA 매핑. */
@IntegrationTest
class AccountSchemaTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	UserRepository userRepository;

	@Autowired
	UserIdentityRepository identityRepository;

	@Test
	@DisplayName("TASK-2#1 계정·인증수단·비밀번호·refresh 토큰·Spring Session 테이블이 Flyway로 만들어진다")
	void tablesExist() {
		List<String> tables = jdbcTemplate.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public'", String.class);

		assertThat(tables).contains("users", "user_identities", "local_credentials", "refresh_tokens",
				"spring_session", "spring_session_attributes");
	}

	@Test
	@DisplayName("TASK-2#1 새 계정은 USER·ACTIVE 이고 email·phone 없이 저장된다")
	void newUserDefaults() {
		User saved = userRepository.saveAndFlush(User.create("닉네임"));

		User found = userRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getRole()).isEqualTo(Role.USER);
		assertThat(found.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(found.getEmail()).isNull();
		assertThat(found.getPhone()).isNull();
		assertThat(found.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("TASK-2#1 한 계정에 여러 인증수단을 붙이고 (provider, subject)로 찾는다")
	void identitiesLookupByProviderAndSubject() {
		User user = userRepository.saveAndFlush(User.create("멀티"));
		String googleSub = "g-" + UUID.randomUUID();
		identityRepository.saveAndFlush(UserIdentity.link(user, AuthProvider.GOOGLE, googleSub, "a@example.com"));
		identityRepository.saveAndFlush(UserIdentity.link(user, AuthProvider.KAKAO, "k-" + UUID.randomUUID(), null));

		UserIdentity found = identityRepository.findByProviderAndSubject(AuthProvider.GOOGLE, googleSub).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getLinkedAt()).isNotNull();
		assertThat(identityRepository.findByUserId(user.getId())).hasSize(2);
	}

	@Test
	@DisplayName("TASK-2#1 같은 (provider, subject) 인증수단은 두 번 저장할 수 없다")
	void providerSubjectIsUnique() {
		User a = userRepository.saveAndFlush(User.create("a"));
		User b = userRepository.saveAndFlush(User.create("b"));
		String subject = "gh-" + UUID.randomUUID();
		identityRepository.saveAndFlush(UserIdentity.link(a, AuthProvider.GITHUB, subject, null));

		assertThatThrownBy(() -> identityRepository.saveAndFlush(UserIdentity.link(b, AuthProvider.GITHUB, subject, null)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("TASK-2#1 provider 는 LOCAL·GOOGLE·GITHUB·NAVER·KAKAO·COGNITO·PHONE 7가지다")
	void providers() {
		assertThat(AuthProvider.values()).extracting(Enum::name)
				.containsExactly("LOCAL", "GOOGLE", "GITHUB", "NAVER", "KAKAO", "COGNITO", "PHONE");
	}

}
