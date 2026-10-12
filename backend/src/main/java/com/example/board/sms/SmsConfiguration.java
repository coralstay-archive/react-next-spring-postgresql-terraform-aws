package com.example.board.sms;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.example.board.security.ProductionMode;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;

/** {@code app.sms.provider} 로 {@link SmsSender} 구현을 고른다 (TASK-7#1). AWS 클라이언트는 aws 일 때만 만든다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
public class SmsConfiguration {

	@Bean
	SmsSender smsSender(SmsProperties properties, ObjectProvider<PinpointSmsVoiceV2Client> client,
			Environment environment) {
		return switch (properties.provider()) {
			case "memory" -> new InMemorySmsSender();
			case "aws" -> new EndUserMessagingSmsSender(client.getObject(), properties.aws());
			// 운영 모드에서는 인증번호 원문을 로그에 남기지 않는다(TASK-21#4 B3). 보통은 기동 검사가 먼저 막는다
			default -> new ConsoleSmsSender(!ProductionMode.isProduction(environment));
		};
	}

	/** 기동 시 네트워크를 쓰지 않는다. 자격 증명은 첫 호출 때 SDK 기본 체인(EC2 인스턴스 프로파일)에서 얻는다. */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "app.sms.provider", havingValue = "aws")
	PinpointSmsVoiceV2Client pinpointSmsVoiceV2Client(SmsProperties properties) {
		return PinpointSmsVoiceV2Client.builder()
			.region(Region.of(properties.aws().region()))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
	}

	@Bean
	PhoneNumbers phoneNumbers(SmsProperties properties) {
		return new PhoneNumbers(properties.defaultRegion());
	}

}
