package com.kmarket.navigator.backend.news.domain;

import java.time.Instant;
import java.util.UUID;

public record NewsQuery(
	String query,
	String stockCode,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	NewsImportance marketImpactImportance,
	boolean watchlistOnly,
	UUID userId,
	Instant from,
	Instant to,
	NewsSort sort,
	NewsCursor cursor,
	int limit
) {
}
