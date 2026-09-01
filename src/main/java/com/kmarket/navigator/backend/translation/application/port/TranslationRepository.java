package com.kmarket.navigator.backend.translation.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TranslationJob;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationView;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

import tools.jackson.databind.JsonNode;

public interface TranslationRepository {

	Optional<TranslationView> find(
		TranslationKind kind,
		String sourceHash,
		String targetLocale,
		String version
	);

	TranslationView request(
		TranslationKind kind,
		String sourceHash,
		String canonicalSource,
		JsonNode context,
		String targetLocale,
		String version,
		Instant now
	);

	void prioritize(UUID id, Instant now);

	List<TranslationJob> claim(int limit, String workerId, Instant now, Instant staleBefore);

	List<TitleTranslationJob> claimNewsTitles(
		int limit,
		String workerId,
		Instant now,
		Instant staleBefore
	);

	void complete(UUID id, GeneratedTranslation generated, Instant now);

	void completeNewsTitle(GeneratedTitle generated, Instant now);

	void fail(UUID id, int attempts, String errorCode, Instant now, Duration delay);
}
