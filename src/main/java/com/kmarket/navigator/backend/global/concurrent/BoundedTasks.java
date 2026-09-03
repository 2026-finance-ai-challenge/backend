package com.kmarket.navigator.backend.global.concurrent;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class BoundedTasks {

	private BoundedTasks() { }

	public static <T> void forEach(List<T> values, int concurrency, Consumer<T> action) {
		if (concurrency < 1) throw new IllegalArgumentException("Concurrency must be positive");
		if (values.isEmpty()) return;
		if (values.size() == 1 || concurrency == 1) {
			values.forEach(action);
			return;
		}
		// 가상 스레드라도 외부 API와 DB의 동시 요청 수는 제한한다.
		try (var executor = Executors.newFixedThreadPool(Math.min(concurrency, values.size()),
			Thread.ofVirtual().name("pipeline-", 0).factory())) {
			var futures = executor.invokeAll(values.stream()
				.<Callable<Void>>map(value -> () -> { action.accept(value); return null; }).toList());
			for (var future : futures) future.get();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Pipeline interrupted", exception);
		}
		catch (ExecutionException exception) {
			throw new IllegalStateException("Pipeline task failed", exception.getCause());
		}
	}
}
