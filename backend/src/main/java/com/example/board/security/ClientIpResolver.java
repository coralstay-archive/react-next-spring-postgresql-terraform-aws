package com.example.board.security;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>헤더가 없거나 형식이 맞지 않으면 remote address 로 돌아가고 WARN 을 남긴다(1분에 한 번, TASK-21#2). 운영 모드에서 헤더를
 * 설정하지 않으면 기동하지 않는다({@link ProductionSafetyCheck}). 클라이언트가 마음대로 넣을 수 있는 {@code X-Forwarded-For} 의 첫
 * 값은 어떤 경우에도 쓰지 않는다.
 */
@Component
public class ClientIpResolver {

	private static final Pattern IPV4 = Pattern
		.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

	private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]{2,39}(\\.[0-9.]+)?$");

	private static final Pattern PORT = Pattern.compile("^\\d{1,5}$");

	/** 헤더 누락 경고 간격 (TASK-21#2). 헤더가 빠진 요청이 몰려도 로그가 폭주하지 않게 한다. */
	static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

	private static final Log log = LogFactory.getLog(ClientIpResolver.class);

	private final String header;

	private final Clock clock;

	/** 마지막 경고 시각(epoch ms). 아직 없으면 Long.MIN_VALUE. */
	private final AtomicLong lastWarnAt = new AtomicLong(Long.MIN_VALUE);

	/** 마지막 경고 뒤로 경고 없이 넘어간 건수. */
	private final AtomicLong suppressed = new AtomicLong();

	@Autowired
	public ClientIpResolver(@Value("${app.client-ip.header:}") String header) {
		this(header, Clock.systemUTC());
	}

	ClientIpResolver(String header, Clock clock) {
		this.header = header == null ? "" : header.strip();
		this.clock = clock;
	}

	public String resolve(HttpServletRequest request) {
		if (!header.isEmpty()) {
			String fromHeader = ipPart(request.getHeader(header));
			if (fromHeader != null) {
				return fromHeader;
			}
			warnMissingHeader();
		}
		return request.getRemoteAddr();
	}

	/**
	 * 신뢰 헤더를 설정했는데 없거나 형식이 틀렸다 (TASK-21#2, doc-2 BE-9): CloudFront 오리진 요청 정책이 헤더를 빼먹었거나 CloudFront 를
	 * 거치지 않은 요청이다. 그러면 remote address(CloudFront 엣지 IP)를 여러 사용자가 공유해 IP 별 OTP 한도가 서비스 거부가 된다.
	 * {@link #WARN_INTERVAL} 에 한 번만 남기고, 헤더 값·IP 는 싣지 않는다.
	 */
	private void warnMissingHeader() {
		long now = clock.millis();
		long last = lastWarnAt.get();
		if (last != Long.MIN_VALUE && now - last < WARN_INTERVAL.toMillis()) {
			suppressed.incrementAndGet();
			return;
		}
		if (!lastWarnAt.compareAndSet(last, now)) {
			suppressed.incrementAndGet();
			return;
		}
		long skipped = suppressed.getAndSet(0);
		log.warn("클라이언트 IP 헤더 " + header + " 가 요청에 없거나 형식이 틀려 remote address 를 쓴다. CloudFront 오리진 요청 정책과"
				+ " CloudFront 를 거치지 않는 접근을 확인한다 (" + WARN_INTERVAL.toMinutes() + "분에 한 번 기록"
				+ (skipped > 0 ? ", 지난 경고 뒤 " + skipped + "건 더" : "") + ")");
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
