package com.kmarket.navigator.backend.identity.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface LoginGuardRepository {
	Optional<Duration> retryAfter(String guardKey, Instant now);

	void recordFailure(String guardKey, Instant now);

	void clear(String guardKey);
}
