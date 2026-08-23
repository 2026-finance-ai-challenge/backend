package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NewsArticle(
	UUID id,
	UUID clusterId,
	String originalTitle,
	String originalExcerpt,
	String originalBody,
	String englishTitle,
	String englishBody,
	String what,
	String why,
	String impact,
	String eventType,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	NewsImportance marketImpactImportance,
	BigDecimal marketImpactScore,
	BigDecimal eventConfidence,
	BigDecimal sentimentConfidence,
	BigDecimal importanceConfidence,
	BigDecimal marketImpactConfidence,
	String originalUrl,
	String canonicalUrl,
	String publisher,
	String thumbnailUrl,
	NewsContentAvailability contentAvailability,
	NewsAnalysisStatus analysisStatus,
	String modelId,
	String promptVersion,
	Instant publishedAt,
	Instant collectedAt,
	Instant analyzedAt,
	long relatedCoverageCount,
	List<RelatedStock> relatedStocks
) {
	public NewsArticle {
		relatedStocks = List.copyOf(relatedStocks);
	}

	public String sourceText() {
		return originalBody == null || originalBody.isBlank() ? originalExcerpt : originalBody;
	}
}
