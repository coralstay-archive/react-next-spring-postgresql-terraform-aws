package com.example.board.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * TASK-22 (리뷰 L4) 동시성 테스트 헬퍼: 같은 작업을 스레드 {@code n} 개에서 시작 게이트({@link CountDownLatch})로 한꺼번에 풀어 돌리고
 * 결과를 모은다. 모든 스레드가 준비된 뒤에 게이트를 열어 실제로 겹치게 한다. sleep 은 쓰지 않는다.
 */
public final class Parallel {

	private Parallel() {
	}

	public static <T> List<T> run(int n, Supplier<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			CountDownLatch ready = new CountDownLatch(n);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return task.get();
				}));
			}
			if (!ready.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("threads not ready");
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
