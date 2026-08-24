package com.kmarket.navigator.backend.translation.domain;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record TranslationJob(
	UUID id,
	TranslationKind kind,
	String sourceHash,
	String canonicalSource,
	JsonNode context,
	String translationVersion,
	int attempts
) {
}
