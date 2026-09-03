package com.kmarket.navigator.backend.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ChatGenerationTests {
	@Test
	void onlyUnansweredServiceOutagesCanBeRetried() {
		for (String code : new String[] {"AI_INVALID_OUTPUT", "INVALID_CHAT_SELECTION", "INVALID_CHAT_MESSAGE"}) {
			assertThat(generation(ChatGenerationStatus.FAILED, code).retryable()).isFalse();
		}
		assertThat(generation(ChatGenerationStatus.FAILED, "AI_SERVICE_UNAVAILABLE").retryable()).isTrue();
		assertThat(generation(ChatGenerationStatus.COMPLETED, null).retryable()).isFalse();
	}

	private ChatGeneration generation(ChatGenerationStatus status, String code) {
		Instant now = Instant.now();
		return new ChatGeneration(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
			status, 1, code, now, now, now);
	}
}
