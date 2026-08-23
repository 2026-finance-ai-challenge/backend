package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ForeignOwnershipSnapshot(
	long foreignOwnedQuantity,
	Long totalListedQuantity,
	Long foreignLimitQuantity,
	Long availableQuantity,
	BigDecimal ownershipRate,
	BigDecimal limitExhaustionRate,
	LocalDate baseDate,
	Instant collectedAt,
	String source
) {
}
