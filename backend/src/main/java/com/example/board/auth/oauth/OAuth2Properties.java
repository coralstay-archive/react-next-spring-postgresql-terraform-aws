package com.example.board.auth.oauth;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 소셜 로그인 설정 ({@code app.oauth2.*}).
 *
 * @param loginRedirectPath 로그인 성공·실패 후 돌아갈 프론트 경로(공개 주소 기준). 성공 {@code ?status=ok}, 실패 {@code ?error=코드}
 * @param linkRedirectPath 계정 연결 성공·실패 후 돌아갈 프론트 경로. 성공 {@code ?linked=제공자}, 실패 {@code ?error=코드}
 * @param linkIntentTtl 연결 시작({@code /api/me/identities/link/{p}}) 후 콜백까지 허용하는 시간
 * @param registrations 등록 id(google, github, ...) → 클라이언트 설정. client-id 가 비어 있으면 그 제공자는 등록하지 않는다
 */
@ConfigurationProperties("app.oauth2")
public record OAuth2Properties(String loginRedirectPath, String linkRedirectPath, Duration linkIntentTtl,
		Map<String, Registration> registrations) {

	public OAuth2Properties {
		loginRedirectPath = loginRedirectPath == null ? "/auth/callback/" : loginRedirectPath;
		linkRedirectPath = linkRedirectPath == null ? "/account/" : linkRedirectPath;
		linkIntentTtl = linkIntentTtl == null ? Duration.ofMinutes(10) : linkIntentTtl;
		registrations = registrations == null ? Map.of() : Map.copyOf(registrations);
	}

	/**
	 * 제공자 하나의 클라이언트 설정. 엔드포인트 값은 비우면 제공자 기본값({@link OAuth2UserInfoMapper#registration})을 쓴다
	 * (테스트는 WireMock 주소로 덮는다).
	 */
	public record Registration(String clientId, String clientSecret, String authorizationUri, String tokenUri,
			String userInfoUri, String jwkSetUri, String issuerUri) {
	}

}
