package com.example.board.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import com.example.board.auth.AuthUser;
import com.example.board.user.Role;
import com.example.board.user.UserStatus;

/** TASK-2#2 자체 서명 JWT access 토큰 발급·검증. */
class JwtTokenServiceTest {

	static final String SECRET = "0123456789abcdef0123456789abcdef";

	JwtTokenService service = new JwtTokenService(new JwtProperties(SECRET, "board-test", Duration.ofMinutes(5), Duration.ofDays(14)), Clock.systemUTC());

	@Test
	@DisplayName("TASK-2#2 발급한 access 토큰을 검증하면 같은 AuthUser 가 나온다")
	void roundTrip() {
		AuthUser user = new AuthUser(42L, Role.ADMIN, UserStatus.ACTIVE);

		Jwt jwt = service.decode(service.issueAccessToken(user));

		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("board-test");
		assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
		assertThat(service.toAuthUser(jwt)).isEqualTo(user);
	}

	@Test
	@DisplayName("TASK-2#2 다른 발급자(iss) 토큰은 거부한다")
	void rejectsOtherIssuer() {
		JwtTokenService other = new JwtTokenService(new JwtProperties(SECRET, "someone-else", Duration.ofMinutes(5), Duration.ofDays(14)), Clock.systemUTC());
		String token = other.issueAccessToken(new AuthUser(1L, Role.USER, UserStatus.ACTIVE));

		assertThatThrownBy(() -> service.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("TASK-2#2 HMAC 키가 32바이트보다 짧으면 시작할 수 없다")
	void rejectsShortSecret() {
		assertThatThrownBy(() -> new JwtProperties("too-short", "board", Duration.ofMinutes(5), Duration.ofDays(14)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("app.jwt.secret");
	}

}
