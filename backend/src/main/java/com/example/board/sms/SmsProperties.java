package com.example.board.sms;

import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMS 설정 ({@code app.sms.*}).
 *
 * @param provider {@code console}(기본) | {@code memory} | {@code aws}. 비어 있으면 console. 그 밖의 값이면 기동하지 않는다
 * @param defaultRegion {@code +} 없이 들어온 번호를 해석할 지역 (ISO 3166-1, 기본 KR)
 * @param aws {@code provider=aws} 일 때만 쓴다
 */
@ConfigurationProperties("app.sms")
public record SmsProperties(String provider, String defaultRegion, Aws aws) {

	public static final Set<String> PROVIDERS = Set.of("console", "memory", "aws");

	public SmsProperties {
		provider = provider == null || provider.isBlank() ? "console" : provider.strip().toLowerCase(Locale.ROOT);
		if (!PROVIDERS.contains(provider)) {
			throw new IllegalArgumentException("app.sms.provider 는 " + PROVIDERS + " 중 하나여야 한다: " + provider);
		}
		if (defaultRegion == null || defaultRegion.isBlank()) {
			defaultRegion = "KR";
		}
		if (aws == null) {
			aws = new Aws(null, null, null);
		}
	}

	/**
	 * @param region End User Messaging 리전 (기본 us-east-1). 발신 번호·sender ID 를 등록한 리전이어야 한다
	 * @param originationIdentity 발신 번호(E.164)·sender ID·phone pool ARN 등. 비우면 넣지 않는다
	 * @param configurationSetName 이벤트 기록용 configuration set. 비우면 넣지 않는다
	 */
	public record Aws(String region, String originationIdentity, String configurationSetName) {

		public Aws {
			if (region == null || region.isBlank()) {
				region = "us-east-1";
			}
		}

	}

}
