package com.example.board.security;

import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 요청한 클라이언트의 IP (발송 레이트리밋·기록용, TASK-7#4).
 *
 * <p>운영은 CloudFront → EC2 라 remote address 가 CloudFront 엣지의 IP 다. 그래서 {@code app.client-ip.header} 에 신뢰 헤더를
 * 설정하면(운영: {@code CloudFront-Viewer-Address}) 그 값의 IP 부분을 쓴다. 이 헤더는 {@code ip:port} 꼴이고(IPv6 는 괄호 없이
 * {@code 2001:db8::1:443}) CloudFront 가 직접 채우므로 뷰어가 위조할 수 없다 — 단 오리진 요청 정책이 이 헤더를 넘겨야 하고(TASK-16),
 * EC2 가 CloudFront 를 거치지 않는 요청을 받지 않아야 한다(보안 그룹을 CloudFront prefix list 로 제한).
 *
 * <p>헤더가 없거나 형식이 맞지 않으면 remote address 로 돌아간다. 클라이언트가 마음대로 넣을 수 있는 {@code X-Forwarded-For} 의 첫
 * 값은 어떤 경우에도 쓰지 않는다.
 */
@Component
public class ClientIpResolver {

	private static final Pattern IPV4 = Pattern
		.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

	private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,39}(\\.[0-9.]+)?$");

	private static final Pattern PORT = Pattern.compile("^\\d{1,5}$");

	private final String header;

	public ClientIpResolver(@Value("${app.client-ip.header:}") String header) {
		this.header = header == null ? "" : header.strip();
	}

	public String resolve(HttpServletRequest request) {
		if (!header.isEmpty()) {
			String fromHeader = ipPart(request.getHeader(header));
			if (fromHeader != null) {
				return fromHeader;
			}
		}
		return request.getRemoteAddr();
	}

	/** {@code ip:port} 에서 IP 부분. 형식이 틀리면 null. */
	static String ipPart(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		int colon = trimmed.lastIndexOf(':');
		if (colon <= 0 || !PORT.matcher(trimmed.substring(colon + 1)).matches()) {
			return null;
		}
		String ip = trimmed.substring(0, colon);
		if (IPV4.matcher(ip).matches()) {
			return ip;
		}
		if (ip.contains(":") && IPV6.matcher(ip).matches()) {
			return ip;
		}
		return null;
	}

}
