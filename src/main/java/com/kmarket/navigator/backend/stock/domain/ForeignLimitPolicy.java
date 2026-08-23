package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ForeignLimitPolicy(
	String stockCode,
	BigDecimal warningThreshold,
	LocalDate effectiveFrom
) {
}
