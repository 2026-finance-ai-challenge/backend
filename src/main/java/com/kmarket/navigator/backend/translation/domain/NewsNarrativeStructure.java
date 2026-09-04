package com.kmarket.navigator.backend.translation.domain;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class NewsNarrativeStructure {

	private NewsNarrativeStructure() { }

	public static void requireValid(JsonNode result, int expectedParagraphs, boolean complete) {
		if (!result.isObject()) throw invalid();
		requireSummary(result);
		if (result.has("summaries")) {
			requireSummary(result.path("summaries").path("en"));
			requireSummary(result.path("summaries").path("ko"));
		}
		if (!complete) return;
		if (result.has("bodyReady") && !result.path("bodyReady").asBoolean()) throw invalid();
		var paragraphs = result.path("translatedParagraphs");
		if (!paragraphs.isArray() || expectedParagraphs < 1 || paragraphs.size() != expectedParagraphs) {
			throw invalid();
		}
		paragraphs.forEach(NewsNarrativeStructure::requireText);
	}

	private static void requireSummary(JsonNode summary) {
		for (String key : List.of("what", "why", "impact")) requireText(summary.path(key));
	}

	private static void requireText(JsonNode value) {
		if (!value.isString() || value.stringValue().isBlank()) throw invalid();
	}

	private static IllegalArgumentException invalid() {
		return new IllegalArgumentException("News translation structure is incomplete");
	}
}
