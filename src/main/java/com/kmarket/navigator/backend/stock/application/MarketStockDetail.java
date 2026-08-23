package com.kmarket.navigator.backend.stock.application;

import java.math.BigDecimal;

import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

public record MarketStockDetail(
	StockMarketView view,
	BigDecimal currentPriceUsd,
	ExchangeRateSnapshot usdExchangeRate,
	ForeignLimitPolicy foreignLimitPolicy,
	ForeignLimitPrediction foreignLimitPrediction
) {
}
