package com.example.board.auth.oauth;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth 흐름이 끝난 뒤 브라우저를 돌려보낼 프론트 URL (모두 공개 주소 기준 절대 URL).
 *
 * <ul>
 * <li>로그인 성공 {@code {login}?status=ok}, 실패 {@code {login}?error=코드}</li>
 * <li>연결 성공 {@code {link}?linked=제공자}, 실패 {@code {link}?error=코드}</li>
 * </ul>
 * 기본 경로는 {@code /auth/callback/} 와 {@code /account/} (Next static export 의 trailingSlash). 화면은 TASK-13.
 */
public final class OAuth2Redirects {

	private final PublicUrls urls;

	private final OAuth2Properties properties;

	public OAuth2Redirects(PublicUrls urls, OAuth2Properties properties) {
		this.urls = urls;
		this.properties = properties;
	}

	public String loginSucceeded() {
		return url(properties.loginRedirectPath(), "status", "ok");
	}

	public String loginFailed(String error) {
		return url(properties.loginRedirectPath(), "error", error);
	}

	public String linked(String registrationId) {
		return url(properties.linkRedirectPath(), "linked", registrationId);
	}

	public String linkFailed(String error) {
		return url(properties.linkRedirectPath(), "error", error);
	}

	private String url(String path, String name, String value) {
		return UriComponentsBuilder.fromUriString(urls.url(path)).queryParam(name, value).encode().build().toUriString();
	}

}
