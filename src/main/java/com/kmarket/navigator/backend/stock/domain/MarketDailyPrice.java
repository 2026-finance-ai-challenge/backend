package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarketDailyPrice(
	LocalDate tradingDate,
	BigDecimal openPriceKrw,
	BigDecimal highPriceKrw,
	BigDecimal lowPriceKrw,
	BigDecimal closePriceKrw,
	long volume,
	String source
) {
}
