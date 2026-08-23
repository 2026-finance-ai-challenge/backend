package com.kmarket.navigator.backend.news.domain;

import java.time.Instant;

public record NewsQuery(
	String stockCode,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	Instant from,
	Instant to,
	NewsSort sort,
	NewsCursor cursor,
	int limit
) {
}
