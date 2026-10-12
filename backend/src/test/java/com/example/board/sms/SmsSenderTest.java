package com.example.board.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageResponse;

/** TASK-7#1 SmsSender 구현(콘솔·메모리·End User Messaging)과 설정({@code app.sms.provider})에 따른 선택. */
@ExtendWith(OutputCaptureExtension.class)
class SmsSenderTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
		.withUserConfiguration(SmsConfiguration.class);

	/** 네트워크 없이 SendTextMessage 요청만 기록하는 SDK 클라이언트 대역. */
	static final class FakeSmsClient implements PinpointSmsVoiceV2Client {

		final List<SendTextMessageRequest> requests = new ArrayList<>();

		RuntimeException failure;

		@Override
		public SendTextMessageResponse sendTextMessage(SendTextMessageRequest request) {
			requests.add(request);
			if (failure != null) {
				throw failure;
			}
			return SendTextMessageResponse.builder().messageId("msg-" + requests.size()).build();
		}

		@Override
		public String serviceName() {
			return "sms-voice";
		}

		@Override
		public void close() {
		}

	}

	@Test
	@DisplayName("TASK-7#1 provider 를 지정하지 않으면 콘솔 구현이다")
	void defaultsToConsole() {
		context.run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(ConsoleSmsSender.class));
	}

	@Test
	@DisplayName("TASK-7#1 provider=memory 는 메모리 구현(테스트 수신함)이다")
	void memory() {
		context.withPropertyValues("app.sms.provider=memory")
			.run(ctx -> assertThat(ctx).getBean(SmsSender.class).isInstanceOf(InMemorySmsSender.class));
	}

	@Test
	@DisplayName("TASK-7#1 provider=aws 는 End User Messaging 구현이고 SDK 클라이언트를 네트워크 호출 없이 만든다")
	void aws() {
		context.withPropertyValues("app.sms.provider=aws", "app.sms.aws.region=ap-northeast-2").run(ctx -> {
			assertThat(ctx).getBean(SmsSender.class).isInstanceOf(EndUserMessagingSmsSender.class);
			assertThat(ctx).hasSingleBean(PinpointSmsVoiceV2Client.class);
		});
	}

	@Test
	@DisplayName("TASK-7#1 알 수 없는 provider 값이면 기동하지 않는다")
	void unknownProvider() {
		context.withPropertyValues("app.sms.provider=carrier-pigeon").run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	@DisplayName("TASK-7#1 TASK-21#4 콘솔 구현은 메시지를 로그로 남긴다(로컬 개발용). 번호는 마스킹한다")
	void consoleLogs(CapturedOutput output) {
		new ConsoleSmsSender(true).send("+821012345678", "인증번호 123456");
		assertThat(output).contains("to=+82******5678").contains("인증번호 123456").doesNotContain("+821012345678");
	}

	@Test
	@DisplayName("TASK-21#4 TASK-21#B3 운영 모드면 콘솔 구현이라도 메시지(인증번호) 원문을 로그에 남기지 않는다")
	void consoleHidesMessageInProduction(CapturedOutput output) {
		context.withPropertyValues("app.sms.provider=console", "app.public-base-url=https://board.example.com")
			.run(ctx -> ctx.getBean(SmsSender.class).send("+821012345678", "[board] 인증번호 654321"));

		assertThat(output).contains("[SMS console]").doesNotContain("654321").doesNotContain("+821012345678");
	}

	@Test
	@DisplayName("TASK-21#4 로컬(http)에서는 콘솔 구현이 인증번호를 보여 준다(E2E 가 로그에서 읽는다)")
	void consoleRevealsMessageLocally(CapturedOutput output) {
		context.withPropertyValues("app.sms.provider=console", "app.public-base-url=http://localhost:3000")
			.run(ctx -> ctx.getBean(SmsSender.class).send("+821012345678", "[board] 인증번호 654321"));

		assertThat(output).contains("to=+82******5678 message=[board] 인증번호 654321");
	}

	@Test
	@DisplayName("TASK-7#1 메모리 구현은 번호별 수신함을 남기고 마지막 메시지를 꺼낼 수 있다")
	void inMemoryInbox() {
		InMemorySmsSender sender = new InMemorySmsSender();
		sender.send("+821011112222", "first");
		sender.send("+821011112222", "second");
		sender.send("+821033334444", "other");

		assertThat(sender.messagesTo("+821011112222")).containsExactly("first", "second");
		assertThat(sender.lastMessageTo("+821011112222")).hasValue("second");
		assertThat(sender.lastMessageTo("+821099999999")).isEmpty();
	}

	@Test
	@DisplayName("TASK-7#1 End User Messaging: SendTextMessage(목적지, 본문, TRANSACTIONAL, origination identity)를 호출한다")
	void endUserMessagingRequest() {
		FakeSmsClient client = new FakeSmsClient();
		SmsProperties.Aws aws = new SmsProperties.Aws("ap-northeast-2", "+15555550100", "otp-config");
		new EndUserMessagingSmsSender(client, aws).send("+821012345678", "인증번호 123456");

		assertThat(client.requests).singleElement().satisfies(request -> {
			assertThat(request.destinationPhoneNumber()).isEqualTo("+821012345678");
			assertThat(request.messageBody()).isEqualTo("인증번호 123456");
			assertThat(request.messageType()).isEqualTo(MessageType.TRANSACTIONAL);
			assertThat(request.originationIdentity()).isEqualTo("+15555550100");
			assertThat(request.configurationSetName()).isEqualTo("otp-config");
		});
	}

	@Test
	@DisplayName("TASK-7#1 End User Messaging: origination identity·configuration set 이 비어 있으면 요청에 넣지 않는다(계정 기본값)")
	void endUserMessagingOptionalFields() {
		FakeSmsClient client = new FakeSmsClient();
		new EndUserMessagingSmsSender(client, new SmsProperties.Aws("us-east-1", "", null)).send("+821012345678", "x");

		assertThat(client.requests).singleElement().satisfies(request -> {
			assertThat(request.originationIdentity()).isNull();
			assertThat(request.configurationSetName()).isNull();
			assertThat(request.messageType()).isEqualTo(MessageType.TRANSACTIONAL);
		});
	}

	@Test
	@DisplayName("TASK-7#1 End User Messaging: SDK 오류는 SmsDeliveryException 으로 감싼다(번호·본문은 메시지에 싣지 않는다)")
	void endUserMessagingFailure() {
		FakeSmsClient client = new FakeSmsClient();
		client.failure = AwsServiceException.builder().message("throttled").build();
		EndUserMessagingSmsSender sender = new EndUserMessagingSmsSender(client,
				new SmsProperties.Aws("us-east-1", null, null));

		assertThatThrownBy(() -> sender.send("+821012345678", "인증번호 123456"))
			.isInstanceOf(SmsDeliveryException.class)
			.hasMessageNotContaining("123456")
			.hasMessageNotContaining("+821012345678");
	}

}
