package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;
import java.util.List;

public record NewsAnalysis(
	String englishTitle,
	List<String> translatedParagraphs,
	String what,
	String why,
	String impact,
	String eventType,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	BigDecimal eventConfidence,
	BigDecimal sentimentConfidence,
	BigDecimal importanceConfidence,
	BigDecimal marketImpactConfidence,
	String model,
	String promptVersion
) {
	public NewsAnalysis {
		translatedParagraphs = List.copyOf(translatedParagraphs);
	}
}
