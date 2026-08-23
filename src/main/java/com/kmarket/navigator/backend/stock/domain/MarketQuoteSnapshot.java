package com.kmarket.navigator.backend.stock.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketQuoteSnapshot(
	BigDecimal currentPriceKrw,
	BigDecimal changeAmountKrw,
	BigDecimal changeRate,
	BigDecimal openPriceKrw,
	BigDecimal highPriceKrw,
	BigDecimal lowPriceKrw,
	long volume,
	String marketSession,
	Boolean viActive,
	Boolean singlePriceTrading,
	PriceLimitState priceLimitState,
	Boolean tradingHalted,
	String tradingHaltReason,
	boolean statusAvailable,
	MarketDataStatus dataStatus,
	Instant asOf,
	String source
) {
	public boolean tradingCaution() {
		return Boolean.TRUE.equals(viActive)
			|| Boolean.TRUE.equals(singlePriceTrading)
			|| Boolean.TRUE.equals(tradingHalted)
			|| priceLimitState == PriceLimitState.UPPER
			|| priceLimitState == PriceLimitState.LOWER;
	}
}
