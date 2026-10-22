package com.example.board.auth.oauth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth 제공자 HTTP 호출(토큰 교환·userinfo·JWKS) 타임아웃 ({@code app.oauth2.http.*}, TASK-25#3 BE-21). 둘 다 양수여야 하고, 아니면 기동하지
 * 않는다.
 *
 * @param connectTimeout TCP 연결 수립 상한 (기본 3초, env {@code OAUTH_HTTP_CONNECT_TIMEOUT})
 * @param readTimeout 응답을 기다리는 상한 (기본 5초, env {@code OAUTH_HTTP_READ_TIMEOUT}). 콜백 한 번은 토큰·JWKS·userinfo 를 차례로
 * 부르므로 최악은 대략 (연결 + 읽기) × 3 이다
 */
@ConfigurationProperties("app.oauth2.http")
public record OAuth2HttpProperties(Duration connectTimeout, Duration readTimeout) {

	public OAuth2HttpProperties {
		connectTimeout = positive(connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout, "connect-timeout");
		readTimeout = positive(readTimeout == null ? Duration.ofSeconds(5) : readTimeout, "read-timeout");
	}

	private static Duration positive(Duration value, String name) {
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("app.oauth2.http." + name + " 는 양수여야 한다: " + value);
		}
		return value;
	}

}
