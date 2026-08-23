package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketIndexSnapshot(
	String indexCode,
	String indexName,
	BigDecimal currentValue,
	BigDecimal changeAmount,
	BigDecimal changeRate,
	Long volume,
	MarketDataStatus dataStatus,
	Instant asOf,
	String source
) {
}
