package com.example.board.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.testcontainers.containers.MinIOContainer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 첨부 업로드 테스트 클라이언트 (TASK-11). 앱에서 presigned URL 을 받고, 브라우저처럼 그 URL 로 저장소(MinIO)에 직접 PUT 한 뒤
 * confirm 을 부른다.
 */
public final class UploadClient {

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private final Api api;

	public UploadClient(Api api) {
		this.api = api;
	}

	public static String presignBody(String contentType, long size) {
		return "{\"contentType\":" + AuthClient.quote(contentType) + ",\"size\":" + size + "}";
	}

	public HttpResponse<String> presign(String bearer, String contentType, long size) {
		return api.post("/attachments/presign").bearer(bearer).json(presignBody(contentType, size)).send();
	}

	/** presign 200 을 확인하고 본문. */
	public JsonNode presigned(String bearer, String contentType, long size) {
		HttpResponse<String> response = presign(bearer, contentType, size);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return AuthClient.json(response);
	}

	public HttpResponse<String> confirm(String bearer, long attachmentId) {
		return api.post("/attachments/" + attachmentId + "/confirm").bearer(bearer).send();
	}

	/** presign 응답의 uploadUrl 로 PUT 한다. 헤더는 응답의 headers 를 그대로 붙인다(Content-Length 는 HTTP 클라이언트가 붙인다). */
	public static HttpResponse<String> put(JsonNode presigned, byte[] body) {
		return put(presigned, body, Map.of());
	}

	/** {@code overrides} 의 헤더는 응답의 headers 대신 쓴다(서명과 다른 값을 보내는 경우를 시험). */
	public static HttpResponse<String> put(JsonNode presigned, byte[] body, Map<String, String> overrides) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(presigned.get("uploadUrl").asString()))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		presigned.get("headers").properties().forEach(entry -> {
			String name = entry.getKey();
			String value = overrides.keySet().stream().filter(name::equalsIgnoreCase).findFirst().map(overrides::get)
				.orElse(entry.getValue().asString());
			builder.header(name, value);
		});
		return send(builder);
	}

	private static HttpResponse<String> send(HttpRequest.Builder builder) {
		try {
			return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** 응답 headers 에서 {@code omitted} 헤더 하나를 빼고 PUT 한다(서명된 헤더를 빠뜨리는 경우를 시험, TASK-26#2). */
	public static HttpResponse<String> putWithout(JsonNode presigned, byte[] body, String omitted) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(presigned.get("uploadUrl").asString()))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
		presigned.get("headers").properties().forEach(entry -> {
			if (!entry.getKey().equalsIgnoreCase(omitted)) {
				builder.header(entry.getKey(), entry.getValue().asString());
			}
		});
		return send(builder);
	}

	/** presign → PUT → confirm 까지 하고 첨부 id. */
	public long uploaded(String bearer, String contentType, byte[] body) {
		JsonNode presigned = presigned(bearer, contentType, body.length);
		HttpResponse<String> put = put(presigned, body);
		assertThat(put.statusCode()).as(put.body()).isEqualTo(200);
		long id = presigned.get("attachmentId").asLong();
		HttpResponse<String> confirmed = confirm(bearer, id);
		assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
		return id;
	}

	/** 크기만 맞춘 가짜 이미지 바이트. */
	public static byte[] bytes(int size) {
		byte[] body = new byte[size];
		for (int i = 0; i < size; i++) {
			body[i] = (byte) (i % 251);
		}
		return body;
	}

	/** 테스트가 저장소를 직접 들여다볼 S3 클라이언트(앱의 클라이언트는 빈으로 노출하지 않는다). */
	public static S3Client s3(MinIOContainer minio) {
		return S3Client.builder()
			.region(Region.US_EAST_1)
			.endpointOverride(URI.create(minio.getS3URL()))
			.forcePathStyle(true)
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
			.httpClient(UrlConnectionHttpClient.create())
			.build();
	}

}
