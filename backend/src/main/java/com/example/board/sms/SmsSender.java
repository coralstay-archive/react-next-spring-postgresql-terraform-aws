package com.example.board.sms;

/**
 * SMS 발송 (decision-7). 구현은 {@code app.sms.provider} 로 고른다({@link SmsConfiguration}).
 *
 * <ul>
 * <li>{@code console}(로컬 기본): 메시지를 로그로 남긴다. 실제로 보내지 않는다.</li>
 * <li>{@code memory}(테스트): 번호별 수신함에 쌓는다.</li>
 * <li>{@code aws}(운영): AWS End User Messaging SMS {@code SendTextMessage}.</li>
 * </ul>
 */
public interface SmsSender {

	/**
	 * @param e164 E.164 형식 번호({@link PhoneNumbers#normalize})
	 * @param message 본문
	 * @throws SmsDeliveryException 발송 요청이 실패했을 때
	 */
	void send(String e164, String message);

}
