package com.example.board.auth.identity;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.AuthUser;
import com.example.board.auth.oauth.LinkIntent;
import com.example.board.auth.oauth.OAuth2ClientRegistrations;
import com.example.board.auth.oauth.OAuth2Properties;
import com.example.board.auth.oauth.PublicUrls;
import com.example.board.common.ApiException;
import com.example.board.security.BearerRequests;
import com.example.board.security.StepUpRequiredException;

/** 내 로그인 수단 관리 ({@code /api/me/identities}). 인증 필요(보안 체인 기본 규칙). */
@RestController
public class IdentityController {

	private final OAuth2ClientRegistrations registrations;

	private final OAuth2Properties properties;

	private final PublicUrls urls;

	private final IdentityService identities;

	/** 연결 플래그(LinkIntent) 만료 시각 (TASK-23#6). */
	private final Clock clock;

	public IdentityController(OAuth2ClientRegistrations registrations, OAuth2Properties properties, PublicUrls urls,
			IdentityService identities, Clock clock) {
		this.clock = clock;
		this.registrations = registrations;
		this.properties = properties;
		this.urls = urls;
		this.identities = identities;
	}

	/** 내 로그인 수단 목록 (TASK-4#4). 세션·Bearer 공통. */
	@GetMapping("/me/identities")
	public List<IdentityResponse> list(@AuthenticationPrincipal AuthUser user) {
		return identities.list(user.id());
	}

	/** 로그인 수단 해제 (TASK-4#4). 204. 남의 것 404, 마지막 하나 409 (B3). 세션이면 CSRF 필요, 정지 계정 403. */
	@DeleteMapping("/me/identities/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unlink(@PathVariable Long id, @AuthenticationPrincipal AuthUser user) {
		identities.unlink(user.id(), id);
	}

	/**
	 * 계정 연결 시작 (TASK-4#3). 세션에 {@link LinkIntent}(현재 사용자 id + 제공자, 짧은 TTL)를 두고, 공개 주소의
	 * {@code /api/oauth2/authorization/{id}?link=true} 로 302. 콜백이 현재 계정에 identity 를 붙이고 {@code /account/?linked=}
	 * 로 돌려보낸다.
	 *
	 * <p>브라우저 세션 흐름이라 Bearer 요청은 400. 설정되지 않은 제공자는 404 {@code unknown_provider}.
	 */
	@GetMapping("/me/identities/link/{provider}")
	public ResponseEntity<Void> startLink(@PathVariable String provider, @AuthenticationPrincipal AuthUser user,
			HttpServletRequest request) {
		if (BearerRequests.isBearer(request)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "계정 연결은 브라우저 세션으로만 시작할 수 있습니다");
		}
		if (user.stepUpRequired()) {
			// 제한 세션(TASK-7 B4)이 새 수단을 붙여 그 수단으로 step-up 하는 우회를 막는다. GET 이라 쓰기 필터가 보지 않는다.
			throw StepUpRequiredException.apiException();
		}
		String registrationId = provider.toLowerCase(Locale.ROOT);
		if (registrations.findByRegistrationId(registrationId) == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "unknown_provider", "지원하지 않는 로그인 수단입니다");
		}
		LinkIntent.start(request,
				new LinkIntent(user.id(), registrationId, clock.instant().plus(properties.linkIntentTtl())));
		URI authorization = URI.create(urls.url("/api/oauth2/authorization/" + registrationId + "?link=true"));
		return ResponseEntity.status(HttpStatus.FOUND).location(authorization).build();
	}

}
