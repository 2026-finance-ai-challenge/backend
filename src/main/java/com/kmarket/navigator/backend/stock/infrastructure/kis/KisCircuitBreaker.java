package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

@Component
class KisCircuitBreaker {

	private static final int FAILURE_THRESHOLD = 5;
	private static final Duration OPEN_DURATION = Duration.ofMinutes(1);
	private final AtomicInteger consecutiveFailures = new AtomicInteger();
	private final Clock clock = Clock.systemUTC();
	private volatile Instant openUntil = Instant.EPOCH;

	<T> T execute(Supplier<T> supplier) {
		if (clock.instant().isBefore(openUntil)) {
			throw new KisProviderException("KIS provider circuit is open");
		}
		try {
			T result = supplier.get();
			consecutiveFailures.set(0);
			openUntil = Instant.EPOCH;
			return result;
		} catch (RuntimeException exception) {
			if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
				openUntil = clock.instant().plus(OPEN_DURATION);
			}
			throw exception;
		}
	}
}
