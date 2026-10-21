package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.MutableClock;
import com.example.board.support.Parallel;
import com.example.board.support.UploadClient;
import com.example.board.user.User;

/**
 * TASK-22#3 (doc-2 BE-34) presign 사용자별 할당량. 기본: 미확정(PENDING) 첨부 최대 20개 → 넘으면 409
 * {@code too_many_pending_attachments}(시간이 아니라 confirm·정리로 풀리므로 Retry-After 가 없다), presign 1시간 60회 → 넘으면 429
 * {@code rate_limited} + Retry-After.
 */
@IntegrationTest
class PresignQuotaTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MutableClock clock;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		uploads = new UploadClient(new Api(port));
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	private HttpResponse<String> presign(String bearer) {
		return uploads.presign(bearer, "image/png", 1024);
	}

	private long pending(User user) {
		return jdbc.queryForObject("select count(*) from attachments where owner_id = ? and status = 'PENDING'",
				Long.class, user.getId());
	}

	/** 업로드·confirm 을 마친 것처럼 PENDING 을 모두 CONFIRMED 로 바꾼다(시간당 한도만 보려고). */
	private void confirmAll(User user) {
		jdbc.update("update attachments set status = 'CONFIRMED', confirmed_at = now() where owner_id = ?"
				+ " and status = 'PENDING'", user.getId());
	}

	@Test
	@DisplayName("TASK-22#3 미확정(PENDING) 첨부는 사용자별 20개까지: 21번째 presign 은 409 too_many_pending_attachments(행을 만들지 않음)")
	void pendingLimit() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		for (int i = 0; i < 20; i++) {
			assertThat(presign(bearer).statusCode()).as("presign %d", i + 1).isEqualTo(200);
		}

		HttpResponse<String> response = presign(bearer);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
		assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("too_many_pending_attachments");
		assertThat(response.headers().firstValue("Retry-After")).isEmpty();
		assertThat(pending(user)).isEqualTo(20);
		assertThat(presign(fixtures.bearer(fixtures.user())).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#3 같은 사용자가 presign 30개를 동시에 보내도 PENDING 은 정확히 20개: 200 이 20개, 나머지 10개는 409(시간당 60회 안이라 429 없음)")
	void concurrentPresignsDoNotExceedPendingLimit() throws Exception {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);

		List<Integer> statuses = Parallel.run(30, () -> presign(bearer).statusCode());

		Map<Integer, Long> counts = statuses.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		assertThat(counts).as(statuses.toString()).containsOnlyKeys(200, 409).containsEntry(200, 20L).containsEntry(409, 10L);
		assertThat(pending(user)).isEqualTo(20);
	}

	@Test
	@DisplayName("TASK-22#3 PENDING 이 confirm 되면(또는 정리되면) 다시 presign 할 수 있다")
	void pendingLimitFreesOnConfirm() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		for (int i = 0; i < 20; i++) {
			presign(bearer);
		}
		assertThat(presign(bearer).statusCode()).isEqualTo(409);
		jdbc.update("update attachments set status = 'CONFIRMED', confirmed_at = now() where id = (select min(id)"
				+ " from attachments where owner_id = ? and status = 'PENDING')", user.getId());

		assertThat(presign(bearer).statusCode()).isEqualTo(200);
		assertThat(presign(bearer).statusCode()).isEqualTo(409);
	}

	@Test
	@DisplayName("TASK-22#3 presign 은 사용자별 1시간 60회: 61번째는 429 + Retry-After, 1시간 뒤 다시 된다")
	void hourlyLimit() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		for (int i = 0; i < 60; i++) {
			assertThat(presign(bearer).statusCode()).as("presign %d", i + 1).isEqualTo(200);
			if (i % 20 == 19) {
				confirmAll(user);
			}
		}

		HttpResponse<String> limited = presign(bearer);

		assertThat(limited.statusCode()).as(limited.body()).isEqualTo(429);
		assertThat(limited.body()).contains("\"error\":\"rate_limited\"");
		assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(3500L, 3600L);
		assertThat(pending(user)).isZero();
		clock.advance(Duration.ofHours(1));
		// access 토큰 만료도 같은 시계라(TASK-23#6) 1시간 뒤에는 새 토큰으로 요청한다
		assertThat(presign(fixtures.bearer(user)).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("TASK-22#3 거절된 presign(허용하지 않는 타입 400·PENDING 초과 409)은 시간당 한도를 소모하지 않는다")
	void rejectedPresignsDoNotCount() {
		User user = fixtures.user();
		String bearer = fixtures.bearer(user);
		for (int i = 0; i < 10; i++) {
			assertThat(uploads.presign(bearer, "image/svg+xml", 1024).statusCode()).isEqualTo(400);
		}
		for (int i = 0; i < 20; i++) {
			presign(bearer);
		}
		for (int i = 0; i < 30; i++) {
			assertThat(presign(bearer).statusCode()).isEqualTo(409);
		}
		confirmAll(user);

		for (int i = 0; i < 40; i++) {
			assertThat(presign(bearer).statusCode()).as("presign %d", i + 21).isEqualTo(200);
			if (i % 20 == 19) {
				confirmAll(user);
			}
		}
	}

}
