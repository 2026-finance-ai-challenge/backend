package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketForeignNetFlow(
	String marketCode,
	LocalDate tradingDate,
	BigDecimal netPurchaseAmountKrw,
	Instant collectedAt,
	String source
) {
}
