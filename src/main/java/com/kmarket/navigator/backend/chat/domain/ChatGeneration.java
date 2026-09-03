package com.kmarket.navigator.backend.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatGeneration(
	UUID id,
	UUID roomId,
	UUID userMessageId,
	UUID regenerationOfMessageId,
	ChatGenerationStatus status,
	int attempts,
	String lastErrorCode,
	Instant createdAt,
	Instant updatedAt,
	Instant completedAt
) {
	public boolean retryable() {
		return status == ChatGenerationStatus.FAILED
			&& "AI_SERVICE_UNAVAILABLE".equals(lastErrorCode);
	}
}
