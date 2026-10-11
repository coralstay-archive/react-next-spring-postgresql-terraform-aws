package com.example.board.sms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageResponse;

/**
 * AWS End User Messaging SMS (API 이름 {@code pinpoint-sms-voice-v2}, IAM 액션 {@code sms-voice:SendTextMessage}).
 * 인증번호이므로 메시지 유형은 TRANSACTIONAL 이다. origination identity(발신 번호·sender ID·풀 ARN)와 configuration set 은
 * 설정했을 때만 넣고, 비우면 계정 기본값을 쓴다. 자격 증명은 SDK 기본 체인(EC2 인스턴스 프로파일)이다.
 */
public class EndUserMessagingSmsSender implements SmsSender {

	private static final Log log = LogFactory.getLog(EndUserMessagingSmsSender.class);

	private final PinpointSmsVoiceV2Client client;

	private final SmsProperties.Aws properties;

	public EndUserMessagingSmsSender(PinpointSmsVoiceV2Client client, SmsProperties.Aws properties) {
		this.client = client;
		this.properties = properties;
	}

	@Override
	public void send(String e164, String message) {
		SendTextMessageRequest.Builder request = SendTextMessageRequest.builder()
			.destinationPhoneNumber(e164)
			.messageBody(message)
			.messageType(MessageType.TRANSACTIONAL);
		if (hasText(properties.originationIdentity())) {
			request.originationIdentity(properties.originationIdentity());
		}
		if (hasText(properties.configurationSetName())) {
			request.configurationSetName(properties.configurationSetName());
		}
		try {
			SendTextMessageResponse response = client.sendTextMessage(request.build());
			log.info("SMS 발송 요청 성공: to=" + PhoneNumbers.mask(e164) + " messageId=" + response.messageId());
		}
		catch (SdkException ex) {
			throw new SmsDeliveryException("End User Messaging SendTextMessage 실패: " + ex.getClass().getSimpleName(),
					ex);
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
