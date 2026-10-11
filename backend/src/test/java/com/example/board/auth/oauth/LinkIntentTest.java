package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.board.auth.AuthUser;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

/** TASK-4#3 연결 플래그: 시작한 사용자·제공자에게만, 짧은 TTL 안에서만 유효하다. */
class LinkIntentTest {

	static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

	static final LinkIntent INTENT = new LinkIntent(7L, "google", NOW.plusSeconds(600));

	static AuthUser user(long id) {
		return new AuthUser(id, Role.USER, UserStatus.ACTIVE);
	}

	@Test
	@DisplayName("TASK-4#3 같은 사용자·같은 제공자·만료 전이면 유효")
	void valid() {
		assertThat(INTENT.isValidFor(user(7), "google", NOW)).isTrue();
	}

	@Test
	@DisplayName("TASK-4#3 다른 사용자, 다른 제공자, 비로그인, 만료 후에는 무효")
	void invalid() {
		assertThat(INTENT.isValidFor(user(8), "google", NOW)).isFalse();
		assertThat(INTENT.isValidFor(user(7), "github", NOW)).isFalse();
		assertThat(INTENT.isValidFor(null, "google", NOW)).isFalse();
		assertThat(INTENT.isValidFor(user(7), "google", NOW.plusSeconds(600))).isFalse();
	}

}
