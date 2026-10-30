package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.example.board.support.IntegrationTest;

/**
 * TASK-26#4 V15 배포 전 데이터의 유예. V13 이전에 글에서 뗀 확정 첨부는 {@code detached_at} 이 비어 있어 {@code confirmed_at} 기준으로 첫
 * 정리에 바로 지워질 수 있었다 → V15 가 배포 시각으로 채운다. 빈 DB 를 V14 까지 올리고 배포 전 데이터를 넣은 뒤 V15 를 적용해 본다.
 */
@IntegrationTest
class AttachmentCleanupMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Test
	@DisplayName("TASK-26#4 V15: 글에 붙지 않은 확정 첨부(detached_at 없음)는 detached_at = 배포 시각, PENDING·붙은 첨부·이미 뗀 시각이 있는 첨부는 그대로, 정리 시작 시각을 남긴다")
	void backfillsDetachedAtAndRecordsStart() {
		String database = "v15_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		jdbc.execute("CREATE DATABASE " + database);
		String url = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		Flyway.configure()
			.dataSource(url, postgres.getUsername(), postgres.getPassword())
			.locations("classpath:db/migration")
			.target("14")
			.load()
			.migrate();
		JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
		Timestamp old = Timestamp.from(Instant.now().minus(Duration.ofDays(60)));
		Timestamp detachedBefore = Timestamp.from(Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MILLIS));
		long user = db.queryForObject("insert into users (nickname) values ('u') returning id", Long.class);
		long post = db.queryForObject("insert into posts (author_id, title, content) values (?, 't', 'c') returning id",
				Long.class, user);
		String insert = "insert into attachments (owner_id, post_id, s3_key, content_type, size, status, created_at,"
				+ " confirmed_at, detached_at) values (?, ?, ?, 'image/png', 1, ?, ?, ?, ?) returning id";
		long unlinked = db.queryForObject(insert, Long.class, user, null, "uploads/1/a.png", "CONFIRMED", old, old, null);
		long alreadyDetached = db.queryForObject(insert, Long.class, user, null, "uploads/1/b.png", "CONFIRMED", old, old,
				detachedBefore);
		long linked = db.queryForObject(insert, Long.class, user, post, "uploads/1/c.png", "CONFIRMED", old, old, null);
		long pending = db.queryForObject(insert, Long.class, user, null, "uploads/1/d.png", "PENDING", old, null, null);

		Instant before = db.queryForObject("select now()", Timestamp.class).toInstant();
		Flyway.configure()
			.dataSource(url, postgres.getUsername(), postgres.getPassword())
			.locations("classpath:db/migration")
			.target("15")
			.load()
			.migrate();

		String detachedAt = "select detached_at from attachments where id = ?";
		assertThat(db.queryForObject(detachedAt, Timestamp.class, unlinked).toInstant()).as("배포 시각부터 유예")
			.isAfterOrEqualTo(before);
		assertThat(db.queryForObject(detachedAt, Timestamp.class, alreadyDetached)).isEqualTo(detachedBefore);
		assertThat(db.queryForObject(detachedAt, Timestamp.class, linked)).isNull();
		assertThat(db.queryForObject(detachedAt, Timestamp.class, pending)).isNull();
		assertThat(db.queryForObject("select started_at from attachment_cleanup_start", Timestamp.class).toInstant())
			.as("삭제 글 첨부도 배포 시각부터 유예(정리 시작 시각)")
			.isAfterOrEqualTo(before);
	}

}
