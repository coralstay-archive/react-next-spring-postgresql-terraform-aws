package com.example.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * TASK-7#4 클라이언트 IP: CloudFront 뒤에서는 EC2 가 CloudFront 의 IP 를 보므로, 설정한 신뢰 헤더
 * ({@code CloudFront-Viewer-Address: ip:port})의 IP 부분을 쓰고 없거나 이상하면 remote address 로 돌아간다.
 * 클라이언트가 마음대로 넣는 {@code X-Forwarded-For} 는 절대 먼저 믿지 않는다.
 */
class ClientIpResolverTest {

	private static MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("192.0.2.10");
		request.addHeader("X-Forwarded-For", "6.6.6.6");
		return request;
	}

	@Test
	@DisplayName("TASK-7#4 신뢰 헤더를 설정하지 않으면 remote address 다(CloudFront-Viewer-Address·X-Forwarded-For 무시)")
	void defaultsToRemoteAddress() {
		MockHttpServletRequest request = request();
		request.addHeader("CloudFront-Viewer-Address", "203.0.113.5:443");

		assertThat(new ClientIpResolver("").resolve(request)).isEqualTo("192.0.2.10");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "203.0.113.5:46532|203.0.113.5", "2001:db8::1:46532|2001:db8::1",
			"2001:db8:85a3::8a2e:370:7334:443|2001:db8:85a3::8a2e:370:7334" })
	@DisplayName("TASK-7#4 CloudFront-Viewer-Address 는 마지막 ':포트' 를 뗀 IP 부분을 쓴다(IPv4·IPv6)")
	void usesTrustedHeader(String header, String expected) {
		MockHttpServletRequest request = request();
		request.addHeader("CloudFront-Viewer-Address", header);

		assertThat(new ClientIpResolver("CloudFront-Viewer-Address").resolve(request)).isEqualTo(expected);
	}

	@Test
	@DisplayName("TASK-7#4 헤더 이름은 대소문자를 가리지 않는다")
	void headerNameIsCaseInsensitive() {
		MockHttpServletRequest request = request();
		request.addHeader("cloudfront-viewer-address", "203.0.113.7:1234");

		assertThat(new ClientIpResolver("CloudFront-Viewer-Address").resolve(request)).isEqualTo("203.0.113.7");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "evil", "203.0.113.5", "<script>:80", "1.2.3.4, 5.6.7.8:80", "999.1.1.1:80",
			"203.0.113.5:http" })
	@DisplayName("TASK-7#4 신뢰 헤더가 형식에 맞지 않으면 remote address 로 돌아간다")
	void fallsBackOnMalformedHeader(String header) {
		MockHttpServletRequest request = request();
		request.addHeader("CloudFront-Viewer-Address", header);

		assertThat(new ClientIpResolver("CloudFront-Viewer-Address").resolve(request)).isEqualTo("192.0.2.10");
	}

	@Test
	@DisplayName("TASK-7#4 신뢰 헤더가 없으면 remote address (X-Forwarded-For 첫 값을 쓰지 않는다)")
	void fallsBackWhenHeaderMissing() {
		assertThat(new ClientIpResolver("CloudFront-Viewer-Address").resolve(request())).isEqualTo("192.0.2.10");
	}

}
