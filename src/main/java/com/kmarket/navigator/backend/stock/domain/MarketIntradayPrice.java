package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketIntradayPrice(
	Instant timestamp,
	BigDecimal openPriceKrw,
	BigDecimal highPriceKrw,
	BigDecimal lowPriceKrw,
	BigDecimal closePriceKrw,
	long volume,
	String source
) {
}
