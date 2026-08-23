package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateSnapshot(
	String currency,
	BigDecimal krwPerUnit,
	MarketDataStatus dataStatus,
	Instant asOf,
	String source
) {
}
