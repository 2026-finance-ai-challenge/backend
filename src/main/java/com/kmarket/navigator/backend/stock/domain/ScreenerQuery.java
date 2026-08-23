package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;

public record ScreenerQuery(
	String market,
	String sector,
	BigDecimal minChangeRate,
	BigDecimal maxChangeRate,
	Long minVolume,
	Long maxVolume,
	Boolean tradingCaution,
	Boolean watchlistOnly,
	ScreenerSort sort,
	int limit
) {
}
