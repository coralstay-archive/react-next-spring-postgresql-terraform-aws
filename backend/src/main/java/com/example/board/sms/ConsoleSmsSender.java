package com.example.board.sms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 로컬 개발용: 보내지 않고 로그로 남긴다. 운영에서 쓰면 안 된다 — 운영은 {@code SMS_PROVIDER=aws} 이고, 운영 모드에서 console 이면
 * 기동하지 않는다(TASK-21#4, {@code security.ProductionSafetyCheck}).
 *
 * <p>번호는 항상 마스킹한다({@link PhoneNumbers#mask}). 메시지(인증번호) 원문은 {@code revealMessage} 일 때만 남긴다 — 로컬(http)은
 * true 라서 개발자·E2E 가 로그에서 인증번호를 읽고, 운영 모드면 어떤 경우에도 false 다(B3).
 */
public class ConsoleSmsSender implements SmsSender {

	private static final Log log = LogFactory.getLog(ConsoleSmsSender.class);

	private final boolean revealMessage;

	public ConsoleSmsSender(boolean revealMessage) {
		this.revealMessage = revealMessage;
	}

	@Override
	public void send(String e164, String message) {
		String body = revealMessage ? message : "(운영 모드라 본문 숨김, " + (message == null ? 0 : message.length()) + "자)";
		log.info("[SMS console] to=" + PhoneNumbers.mask(e164) + " message=" + body);
	}

}
