package com.kmarket.navigator.backend.stock.application.port;

import java.util.Optional;

import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;

public interface MarketDataGateway {

	boolean configured();

	Optional<MarketQuoteSnapshot> fetchQuote(String stockCode);

	Optional<MarketIndexSnapshot> fetchIndex(String indexCode);
}
