package com.example.board.auth.oauth;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.util.StringUtils;

import com.example.board.auth.oauth.OAuth2Properties.Registration;

/**
 * 설정된 제공자만 담은 {@link ClientRegistrationRepository}.
 *
 * <p>client-id 가 비어 있는 제공자는 등록하지 않는다. 그래서 OAuth 비밀값이 하나도 없는 로컬·테스트 환경에서도 앱이 그대로 뜨고,
 * 그 제공자로 시작하면 {@code ?error=unknown_provider} 로 돌아간다. (스프링 기본 {@code InMemoryClientRegistrationRepository} 는
 * 빈 목록을 허용하지 않아 직접 둔다.)
 *
 * <p>redirect-uri 는 요청 URL({@code {baseUrl}}) 대신 공개 주소로 고정한다: {@code {PUBLIC_BASE_URL}/api/login/oauth2/code/{id}}.
 * 제공자 콘솔에 등록할 값도 이것이다.
 */
public final class OAuth2ClientRegistrations implements ClientRegistrationRepository, Iterable<ClientRegistration> {

	/** 콜백 경로 (context-path {@code /api} 포함, 스프링 기본 {@code /login/oauth2/code/{registrationId}}). */
	public static final String CALLBACK_PATH = "/api/login/oauth2/code/";

	private final Map<String, ClientRegistration> registrations;

	private OAuth2ClientRegistrations(Map<String, ClientRegistration> registrations) {
		this.registrations = Collections.unmodifiableMap(registrations);
	}

	public static OAuth2ClientRegistrations from(OAuth2Properties properties, List<OAuth2UserInfoMapper> mappers,
			PublicUrls urls) {
		Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
		for (OAuth2UserInfoMapper mapper : mappers) {
			String id = mapper.registrationId();
			Registration config = properties.registrations().get(id);
			if (config == null || !StringUtils.hasText(config.clientId())) {
				continue;
			}
			ClientRegistration.Builder builder = mapper.registration()
				.clientId(config.clientId().strip())
				.clientSecret(config.clientSecret() == null ? null : config.clientSecret().strip())
				.redirectUri(urls.url(CALLBACK_PATH + id));
			override(config.authorizationUri(), builder::authorizationUri);
			override(config.tokenUri(), builder::tokenUri);
			override(config.userInfoUri(), builder::userInfoUri);
			override(config.jwkSetUri(), builder::jwkSetUri);
			override(config.issuerUri(), builder::issuerUri);
			registrations.put(id, builder.build());
		}
		return new OAuth2ClientRegistrations(registrations);
	}

	private static void override(String value, Consumer<String> setter) {
		if (StringUtils.hasText(value)) {
			setter.accept(value.strip());
		}
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		return registrationId == null ? null : registrations.get(registrationId);
	}

	@Override
	public Iterator<ClientRegistration> iterator() {
		return registrations.values().iterator();
	}

}
