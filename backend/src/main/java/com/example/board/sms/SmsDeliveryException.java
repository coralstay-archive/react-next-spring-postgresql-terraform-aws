package com.example.board.sms;

/** SMS 발송 요청 실패. 메시지에 번호·본문(인증번호)을 싣지 않는다. */
public class SmsDeliveryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SmsDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}

}
