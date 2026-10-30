package com.example.board.attachment;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 테스트 전용 저장소 데코레이터 (TASK-26). 통합 테스트 컨텍스트의 {@code @Primary} {@link UploadStorage} 로, 평소에는 실제 저장소(MinIO)에
 * 그대로 넘긴다. 테스트는 작업별로
 * <ul>
 * <li>다른 저장소로 돌리거나({@link #route}, 예: 잘못된 자격 증명·5xx·닫힌 포트를 쓰는 {@link S3UploadStorage} → S3 오류),</li>
 * <li>작업 직전·직후에 끼어들 수 있다({@link #before}·{@link #after}, 예: confirm 도중 정리 작업이 행을 지운 상황).</li>
 * </ul>
 * presign 서명 때 트랜잭션이 열려 있었는지도 기록한다(TASK-26#3). 컨텍스트를 공유하므로 쓴 테스트는 끝날 때 {@link #reset()} 한다.
 */
public final class StorageProbe implements UploadStorage {

	public enum Op {
		PRESIGN, HEAD, CLEAR_TAG, DELETE
	}

	private final UploadStorage real;

	private final Map<Op, UploadStorage> routes = new ConcurrentHashMap<>();

	private final Map<Op, Consumer<String>> before = new ConcurrentHashMap<>();

	private final Map<Op, Consumer<String>> after = new ConcurrentHashMap<>();

	private volatile Boolean presignInTransaction;

	public StorageProbe(UploadStorage real) {
		this.real = real;
	}

	public void route(Op op, UploadStorage storage) {
		routes.put(op, storage);
	}

	public void before(Op op, Consumer<String> hook) {
		before.put(op, hook);
	}

	public void after(Op op, Consumer<String> hook) {
		after.put(op, hook);
	}

	/** 마지막 presign 서명 때 트랜잭션이 열려 있었는지. 서명이 없었으면 null. */
	public Boolean presignInTransaction() {
		return presignInTransaction;
	}

	public void reset() {
		routes.clear();
		before.clear();
		after.clear();
		presignInTransaction = null;
	}

	private UploadStorage target(Op op) {
		return routes.getOrDefault(op, real);
	}

	private void hook(Map<Op, Consumer<String>> hooks, Op op, String key) {
		Consumer<String> hook = hooks.get(op);
		if (hook != null) {
			hook.accept(key);
		}
	}

	@Override
	public boolean available() {
		return real.available();
	}

	@Override
	public PresignedUpload presignPut(String key, String contentType, long size, Duration ttl) {
		presignInTransaction = TransactionSynchronizationManager.isActualTransactionActive();
		hook(before, Op.PRESIGN, key);
		PresignedUpload upload = target(Op.PRESIGN).presignPut(key, contentType, size, ttl);
		hook(after, Op.PRESIGN, key);
		return upload;
	}

	@Override
	public Optional<StoredObject> head(String key) {
		hook(before, Op.HEAD, key);
		Optional<StoredObject> stored = target(Op.HEAD).head(key);
		hook(after, Op.HEAD, key);
		return stored;
	}

	@Override
	public void clearPendingTag(String key) {
		hook(before, Op.CLEAR_TAG, key);
		target(Op.CLEAR_TAG).clearPendingTag(key);
		hook(after, Op.CLEAR_TAG, key);
	}

	@Override
	public void delete(String key) {
		hook(before, Op.DELETE, key);
		target(Op.DELETE).delete(key);
		hook(after, Op.DELETE, key);
	}

}
