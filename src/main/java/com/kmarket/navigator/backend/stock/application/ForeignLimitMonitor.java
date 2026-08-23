package com.kmarket.navigator.backend.stock.application;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

public record ForeignLimitMonitor(
	StockMarketView view,
	ForeignLimitPolicy policy,
	boolean warning,
	ForeignLimitPrediction prediction
) {
}
