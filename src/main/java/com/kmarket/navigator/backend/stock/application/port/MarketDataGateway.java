package com.kmarket.navigator.backend.stock.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlow;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;

public interface MarketDataGateway {

	boolean configured();

	Optional<MarketQuoteSnapshot> fetchQuote(String stockCode);

	Optional<MarketIndexSnapshot> fetchIndex(String indexCode);

	default List<MarketDailyPrice> fetchDailyPrices(String stockCode, LocalDate from, LocalDate to) {
		return List.of();
	}

	default List<MarketForeignNetFlow> fetchForeignNetFlows(LocalDate tradingDate) {
		return List.of();
	}
}
