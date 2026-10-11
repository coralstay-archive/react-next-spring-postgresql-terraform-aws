package com.example.board.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 자체 서명 JWT 설정 ({@code app.jwt.*}).
 *
 * @param secret HMAC-SHA256 키. 32바이트 이상이어야 하며, 짧으면 애플리케이션이 시작하지 않는다.
 * @param issuer {@code iss} 클레임. 검증 때도 같은 값을 요구한다.
 * @param accessTokenTtl access 토큰 수명.
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, String issuer, Duration accessTokenTtl) {

	public static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException(
					"app.jwt.secret 은 HS256 용으로 " + MIN_SECRET_BYTES + "바이트 이상이어야 한다 (env JWT_SECRET)");
		}
		if (issuer == null || issuer.isBlank()) {
			issuer = "board";
		}
		if (accessTokenTtl == null) {
			accessTokenTtl = Duration.ofMinutes(15);
		}
	}

}
