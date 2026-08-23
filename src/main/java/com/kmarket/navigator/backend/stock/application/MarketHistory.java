package com.kmarket.navigator.backend.stock.application;

import java.util.List;

import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;

public record MarketHistory(
	String stockCode,
	MarketDataStatus dataStatus,
	List<MarketDailyPrice> items
) {
}
