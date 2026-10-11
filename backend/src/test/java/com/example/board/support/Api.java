package com.example.board.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 실제 포트로 HTTP 요청을 보내는 작은 클라이언트. 쿠키·헤더를 손으로 붙여서 세션/Bearer 경로를 그대로 탄다.
 * 리다이렉트는 따라가지 않는다(로그인 페이지 리다이렉트가 없음을 검증하기 위해).
 */
public final class Api {

	private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private final int port;

	public Api(int port) {
		this.port = port;
	}

	public Request get(String path) {
		return new Request("GET", path);
	}

	public Request post(String path) {
		return new Request("POST", path);
	}

	public Request put(String path) {
		return new Request("PUT", path);
	}

	public Request patch(String path) {
		return new Request("PATCH", path);
	}

	public Request delete(String path) {
		return new Request("DELETE", path);
	}

	public Request method(String method, String path) {
		return new Request(method, path);
	}

	/** 응답의 Set-Cookie 헤더에서 이름이 같은 쿠키 값을 꺼낸다. */
	public static Optional<String> cookie(HttpResponse<?> response, String name) {
		return setCookie(response, name).map(header -> header.substring(name.length() + 1).split(";", 2)[0]);
	}

	/** 응답의 Set-Cookie 헤더 원문 중 이름이 같은 것. */
	public static Optional<String> setCookie(HttpResponse<?> response, String name) {
		return response.headers().allValues("Set-Cookie").stream().filter(h -> h.startsWith(name + "=")).findFirst();
	}

	public final class Request {

		private final String method;

		private final String path;

		private final Map<String, String> cookies = new LinkedHashMap<>();

		private final List<String[]> headers = new ArrayList<>();

		private String body;

		private Request(String method, String path) {
			this.method = method;
			this.path = path;
		}

		public Request header(String name, String value) {
			this.headers.add(new String[] { name, value });
			return this;
		}

		public Request bearer(String token) {
			return header("Authorization", "Bearer " + token);
		}

		public Request cookie(String name, String value) {
			this.cookies.put(name, value);
			return this;
		}

		/** {@code SESSION=...} 처럼 이름=값 형태의 쿠키 하나를 붙인다. */
		public Request cookie(String pair) {
			String[] parts = pair.split("=", 2);
			return cookie(parts[0], parts[1]);
		}

		/** CSRF 쿠키와 헤더를 같이 붙인다(더블 서브밋). */
		public Request csrf(String token) {
			cookie("XSRF-TOKEN", token);
			return header("X-XSRF-TOKEN", token);
		}

		public Request json(String json) {
			this.body = json;
			return header("Content-Type", "application/json");
		}

		public HttpResponse<String> send() {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
			headers.forEach(h -> builder.header(h[0], h[1]));
			if (!cookies.isEmpty()) {
				builder.header("Cookie", String.join("; ",
						cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
			}
			try {
				return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}

	}

}
