package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ForeignLimitPrediction(
	BigDecimal minRate,
	BigDecimal baseRate,
	BigDecimal maxRate,
	int observationCount,
	int observationWindowDays,
	BigDecimal confidence,
	String modelVersion,
	LocalDate baseDate,
	Instant calculatedAt,
	String source
) {
}
