package com.kmarket.navigator.backend.translation.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.TranslationJob;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationView;

import tools.jackson.databind.JsonNode;

public interface TranslationRepository {

	Optional<TranslationView> find(TranslationKind kind, String sourceHash, String version);

	TranslationView request(
		TranslationKind kind,
		String sourceHash,
		String canonicalSource,
		JsonNode context,
		String version,
		Instant now
	);

	List<TranslationJob> claim(int limit, String workerId, Instant now, Instant staleBefore);

	void complete(UUID id, GeneratedTranslation generated, Instant now);

	void fail(UUID id, int attempts, String errorCode, Instant now, Duration delay);
}
