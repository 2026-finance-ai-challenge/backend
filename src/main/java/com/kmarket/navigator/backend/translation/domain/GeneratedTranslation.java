package com.kmarket.navigator.backend.translation.domain;

import tools.jackson.databind.JsonNode;

public record GeneratedTranslation(
	String sourceHash,
	String targetLocale,
	String translationVersion,
	JsonNode result,
	String modelId,
	String promptVersion
) {
}
