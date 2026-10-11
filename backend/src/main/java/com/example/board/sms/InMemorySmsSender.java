package com.example.board.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 테스트용 수신함. 번호(E.164)별로 받은 메시지를 순서대로 쌓는다. */
public class InMemorySmsSender implements SmsSender {

	private final Map<String, List<String>> inbox = new ConcurrentHashMap<>();

	@Override
	public void send(String e164, String message) {
		inbox.computeIfAbsent(e164, key -> Collections.synchronizedList(new ArrayList<>())).add(message);
	}

	public List<String> messagesTo(String e164) {
		List<String> messages = inbox.get(e164);
		if (messages == null) {
			return List.of();
		}
		synchronized (messages) {
			return List.copyOf(messages);
		}
	}

	public Optional<String> lastMessageTo(String e164) {
		List<String> messages = messagesTo(e164);
		return messages.isEmpty() ? Optional.empty() : Optional.of(messages.getLast());
	}

	public void clear() {
		inbox.clear();
	}

}
