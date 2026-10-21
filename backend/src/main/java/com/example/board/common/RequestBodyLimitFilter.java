package com.example.board.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 요청 본문 크기 상한 (TASK-23#2, BE-4). 기본 64KB({@code app.request.max-body-size}). 가장 큰 정상 요청은 글 작성·수정(한글 제목 100자 +
 * 본문 20000자 ≈ 60KB, UTF-8 3바이트)이다. 첨부 이미지는 S3 presigned PUT 으로 올리므로 API 본문으로 오지 않는다.
 * <ul>
 * <li>{@code Content-Length} 가 넘으면 본문을 읽지 않고 바로 413 {@code payload_too_large} JSON.</li>
 * <li>길이를 모르는(chunked) 본문은 읽는 만큼 센다. 넘는 순간 {@link TooLargeException} 을 던지고 {@link ApiExceptionHandler} 가
 * 같은 413 으로 바꾼다.</li>
 * <li>chunked form 본문({@code application/x-www-form-urlencoded})은 컨테이너가 {@code getParameter} 때 이 스트림을 거치지 않고 직접
 * 읽는다(CSRF 필터의 {@code _csrf} 조회 등). 그래서 여기서 먼저 파싱하게 하고(상한은 {@code server.tomcat.max-http-form-post-size}),
 * 컨테이너가 너무 크다고 버렸으면 같은 413.</li>
 * <li>multipart 는 파싱하지 않는다({@code spring.servlet.multipart.enabled=false}) — 본문은 읽히지 않고 415 등으로 끝난다.</li>
 * </ul>
 */
public class RequestBodyLimitFilter extends OncePerRequestFilter {

	/** Tomcat 이 파라미터 파싱에 실패한 이유를 싣는 요청 속성(Globals.PARAMETER_PARSE_FAILED_REASON_ATTR). */
	static final String PARSE_FAILED_REASON = "org.apache.catalina.parameter_parse_failed_reason";

	private final long maxBytes;

	private final JsonMapper json;

	public RequestBodyLimitFilter(long maxBytes, JsonMapper json) {
		this.maxBytes = maxBytes;
		this.json = json;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long length = request.getContentLengthLong();
		if (length > maxBytes || (length < 0 && isForm(request) && formTooLarge(request))) {
			tooLarge(response);
			return;
		}
		chain.doFilter(length < 0 ? new LimitedRequest(request, maxBytes) : request, response);
	}

	private static boolean isForm(HttpServletRequest request) {
		String type = request.getContentType();
		if (type == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(type));
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/** 컨테이너가 form 본문을 상한(max-http-form-post-size)까지만 읽고 버렸는가. 파라미터는 여기서 한 번 파싱된다(뒤에서는 캐시를 쓴다). */
	private static boolean formTooLarge(HttpServletRequest request) {
		request.getParameterMap();
		return "POST_TOO_LARGE".equals(String.valueOf(request.getAttribute(PARSE_FAILED_REASON)));
	}

	private void tooLarge(HttpServletResponse response) throws IOException {
		HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getOutputStream()
			.write(json.writeValueAsBytes(ApiErrors.body(status, ApiErrors.code(status), ApiErrors.message(status), null)));
	}

	/** 길이를 모르는 본문이 상한을 넘었다. 413 {@code payload_too_large} 로 응답한다. */
	public static final class TooLargeException extends IOException {

		private static final long serialVersionUID = 1L;

		TooLargeException(long maxBytes) {
			super("요청 본문이 " + maxBytes + "바이트를 넘었습니다");
		}

	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private final long maxBytes;

		private ServletInputStream stream;

		LimitedRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (stream == null) {
				stream = new LimitedInputStream(super.getInputStream(), maxBytes);
			}
			return stream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			String encoding = getCharacterEncoding();
			Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}

	}

	private static final class LimitedInputStream extends ServletInputStream {

		private final ServletInputStream delegate;

		private final long maxBytes;

		private long read;

		LimitedInputStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int b = delegate.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = delegate.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) throws TooLargeException {
			read += n;
			if (read > maxBytes) {
				throw new TooLargeException(maxBytes);
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener listener) {
			delegate.setReadListener(listener);
		}

	}

}
