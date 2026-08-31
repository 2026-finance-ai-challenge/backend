package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketForeignNetFlowSummary(
	LocalDate tradingDate,
	BigDecimal netPurchaseAmountKrw,
	int consecutiveDays,
	MarketDataStatus dataStatus,
	Instant asOf,
	String source
) {
}
