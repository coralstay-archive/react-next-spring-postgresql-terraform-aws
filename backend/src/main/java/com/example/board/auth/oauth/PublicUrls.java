package com.example.board.auth.oauth;

import java.net.URI;

import org.springframework.util.StringUtils;

/**
 * 브라우저가 보는 공개 주소({@code app.public-base-url}, env {@code PUBLIC_BASE_URL}) 기준의 절대 URL.
 *
 * <p>운영에서 앱은 CloudFront 뒤에 있고 CloudFront 는 Host 헤더를 넘기지 않는다(decision-3). 그래서 요청 URL 로 주소를
 * 만들면 EC2 오리진 주소가 나온다. OAuth redirect-uri 와 프론트로 돌려보내는 리다이렉트는 모두 이 설정으로 만든다.
 * 로컬 개발 기본값은 Next 개발 서버({@code http://localhost:3000}, {@code /api} 를 백엔드로 프록시)다.
 */
public final class PublicUrls {

	private final String base;

	public PublicUrls(String baseUrl) {
		if (!StringUtils.hasText(baseUrl)) {
			throw new IllegalArgumentException("app.public-base-url(PUBLIC_BASE_URL) 이 비어 있습니다");
		}
		String trimmed = baseUrl.strip().replaceAll("/+$", "");
		URI uri = URI.create(trimmed);
		if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
			throw new IllegalArgumentException("app.public-base-url 은 http(s) 절대 URL 이어야 합니다: " + baseUrl);
		}
		this.base = trimmed;
	}

	/** 끝 슬래시 없는 공개 주소. 예: {@code https://d111.cloudfront.net} */
	public String base() {
		return base;
	}

	/** {@code path}({@code /} 로 시작)를 붙인 절대 URL. */
	public String url(String path) {
		if (!path.startsWith("/")) {
			throw new IllegalArgumentException("path 는 / 로 시작해야 합니다: " + path);
		}
		return base + path;
	}

}
