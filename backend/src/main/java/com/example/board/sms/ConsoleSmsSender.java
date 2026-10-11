package com.example.board.sms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 로컬 개발용: 보내지 않고 로그로 남긴다(인증번호가 로그에 그대로 보인다). 운영에서 쓰면 안 된다 — 운영은 {@code SMS_PROVIDER=aws}.
 */
public class ConsoleSmsSender implements SmsSender {

	private static final Log log = LogFactory.getLog(ConsoleSmsSender.class);

	@Override
	public void send(String e164, String message) {
		log.info("[SMS console] to=" + e164 + " message=" + message);
	}

}
