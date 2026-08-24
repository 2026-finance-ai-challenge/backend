package com.kmarket.navigator.backend.translation.presentation;

import java.time.Instant;
import java.util.UUID;

import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;

import tools.jackson.databind.JsonNode;

public record TranslationResponse(
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
	public static TranslationResponse from(TranslationView view) {
		return new TranslationResponse(
			view.jobId(), view.sourceHash(), view.targetLocale(), view.translationVersion(),
			view.status(), view.result(), view.modelId(), view.promptVersion(),
			view.generatedAt(), view.errorCode()
		);
	}
}
