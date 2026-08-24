package com.kmarket.navigator.backend.translation.domain;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record TranslationView(
	UUID jobId,
	String sourceHash,
	String targetLocale,
	String translationVersion,
	TranslationStatus status,
	JsonNode result,
	String modelId,
	String promptVersion,
	Instant generatedAt,
	String errorCode
) {
	public static TranslationView notRequested(String sourceHash, String translationVersion) {
		return new TranslationView(
			null, sourceHash, "en", translationVersion, TranslationStatus.NOT_REQUESTED,
			null, null, null, null, null
		);
	}
}
