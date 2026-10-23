package com.example.board.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;

import com.example.board.attachment.OrphanAttachmentCleanup;
import com.example.board.attachment.PendingAttachmentCleanup;
import com.example.board.attachment.StorageProbe;
import com.example.board.attachment.StorageProbe.Op;
import com.example.board.retention.RetentionCleanup;
import com.example.board.support.Api;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.JsonNode;

/**
 * TASK-26#5 BE-37 스케줄 작업 분산 락 (ShedLock, JDBC {@code shedlock} 테이블). 인스턴스가 여럿이어도 같은 작업은 한 번에 하나만 돈다 —
 * 락을 못 잡은 실행은 아무것도 하지 않고 끝난다. 다른 인스턴스를 흉내 내려고 같은 빈을 두 스레드에서 부른다(락은 DB 행이라 인스턴스 구분이
 * 없다). 첫 실행을 객체 삭제 직전에 세워 두고 둘째 실행을 돌려 결정적으로 본다.
 */
@IntegrationTest
class SchedulerLockTest {

	@LocalServerPort
	int port;

	@Autowired
	ApplicationContext context;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StorageProbe probe;

	@Autowired
	PendingAttachmentCleanup pendingCleanup;

	@Autowired
	OrphanAttachmentCleanup orphanCleanup;

	@Autowired
	RetentionCleanup retentionCleanup;

	Api api;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		uploads = new UploadClient(api);
	}

	@AfterEach
	void tearDown() {
		probe.reset();
	}

	private boolean exists(long attachmentId) {
		return jdbc.queryForObject("select count(*) from attachments where id = ?", Integer.class, attachmentId) == 1;
	}

	private static Timestamp ago(Duration age) {
		return Timestamp.from(Instant.now().minus(age));
	}

	/** 정리 대상 PENDING 첨부(24시간 넘음, 객체 있음). */
	private long stalePending(String bearer) {
		JsonNode presigned = uploads.presigned(bearer, "image/png", 10);
		assertThat(UploadClient.put(presigned, UploadClient.bytes(10)).statusCode()).isEqualTo(200);
		long id = presigned.get("attachmentId").asLong();
		jdbc.update("update attachments set created_at = ? where id = ?", ago(Duration.ofHours(25)), id);
		return id;
	}

	/** 정리 대상 확정 첨부(글에 붙이지 않고 7일 넘음). */
	private long staleUnlinked(String bearer) {
		long id = uploads.uploaded(bearer, "image/png", UploadClient.bytes(10));
		jdbc.update("update attachments set created_at = ?, confirmed_at = ? where id = ?", ago(Duration.ofDays(8)),
				ago(Duration.ofDays(8)), id);
		return id;
	}

	/**
	 * 첫 실행(다른 스레드)이 객체 삭제에서 멈춘 동안 새 정리 대상을 만들고 둘째 실행을 돌린다. 둘째가 락을 못 잡고 건너뛰었으면 새 대상이 남는다.
	 * 첫 실행이 끝나 락이 풀리면 셋째 실행이 새 대상을 지운다.
	 */
	private void assertSerialized(Runnable job, LongSupplier staleRow) throws Exception {
		long first = staleRow.getAsLong();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean blocked = new AtomicBoolean();
		probe.before(Op.DELETE, key -> {
			if (blocked.compareAndSet(false, true)) {
				entered.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		});
		CompletableFuture<Void> holder = CompletableFuture.runAsync(job);
		try {
			assertThat(entered.await(30, TimeUnit.SECONDS)).as("첫 실행이 객체 삭제에 들어갔다").isTrue();
			assertThat(exists(first)).isFalse();
			long second = staleRow.getAsLong();

			job.run();

			assertThat(exists(second)).as("락을 잡은 실행이 있는 동안 둘째 실행은 건너뛴다").isTrue();
			release.countDown();
			holder.get(30, TimeUnit.SECONDS);

			job.run();

			assertThat(exists(second)).as("락이 풀린 뒤에는 다시 돈다").isFalse();
		}
		finally {
			release.countDown();
		}
	}

	@Test
	@DisplayName("TASK-26#5 미확정 첨부 정리가 도는 동안 같은 작업의 다른 실행은 건너뛰고, 끝난 뒤에는 다시 돈다")
	void pendingCleanupSerialized() throws Exception {
		String bearer = fixtures.bearer(fixtures.user());
		assertSerialized(pendingCleanup::run, () -> stalePending(bearer));
	}

	@Test
	@DisplayName("TASK-26#5 연결 해제·삭제 글 첨부 정리도 같은 작업의 실행이 겹치지 않는다")
	void orphanCleanupSerialized() throws Exception {
		String bearer = fixtures.bearer(fixtures.user());
		assertSerialized(orphanCleanup::run, () -> staleUnlinked(bearer));
	}

	@Test
	@DisplayName("TASK-26#5 앱의 모든 @Scheduled 작업에 @SchedulerLock(작업마다 다른 이름, lockAtMostFor)이 있고 락 행이 shedlock 테이블에 남는다")
	void everyScheduledJobLocked() {
		List<String> scheduled = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (String bean : context.getBeanDefinitionNames()) {
			Class<?> beanType = context.getType(bean);
			if (beanType == null) {
				continue;
			}
			Class<?> type = ClassUtils.getUserClass(beanType);
			if (!type.getPackageName().startsWith("com.example.board")) {
				continue;
			}
			for (Method method : type.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Scheduled.class)) {
					scheduled.add(type.getSimpleName() + "." + method.getName());
					SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
					assertThat(lock).as("%s.%s 의 @SchedulerLock", type.getSimpleName(), method.getName()).isNotNull();
					assertThat(lock.lockAtMostFor()).isNotBlank();
					assertThat(names.add(lock.name())).as("락 이름 %s 가 겹치지 않는다", lock.name()).isTrue();
				}
			}
		}
		assertThat(scheduled).contains("PendingAttachmentCleanup.run", "OrphanAttachmentCleanup.run", "RetentionCleanup.run");

		pendingCleanup.run();
		orphanCleanup.run();
		retentionCleanup.run();
		assertThat(jdbc.queryForList("select name from shedlock", String.class)).containsAll(names);
	}

}
