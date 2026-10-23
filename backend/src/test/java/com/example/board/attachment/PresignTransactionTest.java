package com.example.board.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.TestcontainersConfiguration;
import com.example.board.attachment.StorageProbe.Op;
import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.support.UploadClient;
import com.example.board.user.User;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * TASK-26#3 BE-35 presign 서명(SDK 자격 증명 조회 — 운영은 IMDS 네트워크 호출일 수 있다)은 DB 트랜잭션 밖에서 한다. 서명이 실패하면 503
 * {@code storage_unavailable} 이고 PENDING 행을 만들지 않는다.
 */
@IntegrationTest
class PresignTransactionTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StorageProbe probe;

	UploadClient uploads;

	@BeforeEach
	void setUp() {
		uploads = new UploadClient(new Api(port));
	}

	@AfterEach
	void tearDown() {
		probe.reset();
	}

	private int attachmentsOf(User user) {
		return jdbc.queryForObject("select count(*) from attachments where owner_id = ?", Integer.class, user.getId());
	}

	@Test
	@DisplayName("TASK-26#3 presign 은 서명하는 동안 DB 트랜잭션(커넥션)을 잡지 않는다. 행은 PENDING 으로 만들어진다")
	void signsOutsideTransaction() {
		User user = fixtures.user();
		uploads.presigned(fixtures.bearer(user), "image/png", 10);

		assertThat(probe.presignInTransaction()).isFalse();
		assertThat(attachmentsOf(user)).isEqualTo(1);
	}

	@Test
	@DisplayName("TASK-26#3 서명이 실패하면(자격 증명 조회 실패) 503 storage_unavailable 이고 PENDING 행이 남지 않는다")
	void signingFailureLeavesNoRow() {
		AwsCredentialsProvider failing = () -> {
			throw SdkClientException.create("자격 증명을 얻지 못함(테스트)");
		};
		try (S3Presigner presigner = S3Presigner.builder()
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create("http://127.0.0.1:1"))
			.credentialsProvider(failing)
			.build()) {
			probe.route(Op.PRESIGN, new S3UploadStorage(null, presigner, TestcontainersConfiguration.UPLOADS_BUCKET));
			User user = fixtures.user();

			HttpResponse<String> response = uploads.presign(fixtures.bearer(user), "image/png", 10);

			assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
			assertThat(AuthClient.json(response).get("error").asString()).isEqualTo("storage_unavailable");
			assertThat(attachmentsOf(user)).isZero();
		}
	}

}
