package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NewsDraft(
	UUID id,
	UUID clusterId,
	String signatureHash,
	String normalizedTitle,
	String providerArticleId,
	String title,
	String excerpt,
	String body,
	String originalUrl,
	String canonicalUrl,
	String canonicalUrlHash,
	String publisher,
	String thumbnailUrl,
	String sourcePolicy,
	Instant publishedAt,
	Instant collectedAt,
	BigDecimal duplicateScore,
	Map<String, BigDecimal> stockConfidences
) {
	public NewsDraft {
		stockConfidences = Map.copyOf(stockConfidences);
	}
}
