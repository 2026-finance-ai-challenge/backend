package com.kmarket.navigator.backend.stock.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.stock.domain.ExchangeRateSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPolicy;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitPrediction;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketDailyPrice;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketForeignNetFlowSummary;
import com.kmarket.navigator.backend.stock.domain.StockIdentity;
import com.kmarket.navigator.backend.stock.domain.StockMarketView;

public interface MarketRepository {

	List<StockIdentity> searchStocks(String query, UUID userId, int limit);

	List<StockMarketView> findStocks(UUID userId);

	Optional<StockMarketView> findStock(String stockCode, UUID userId);

	List<ForeignLimitPolicy> findForeignLimitPolicies();

	List<ForeignOwnershipSnapshot> findForeignOwnershipHistory(UUID securityId, int limit);

	Optional<ForeignLimitPrediction> findLatestForeignLimitPrediction(UUID securityId);

	default Optional<ForeignLimitPrediction> findForeignLimitPredictionBefore(UUID securityId, LocalDate targetDate) {
		return findLatestForeignLimitPrediction(securityId)
			.filter(prediction -> prediction.baseDate() != null && prediction.baseDate().isBefore(targetDate));
	}

	List<MarketIndexSnapshot> findMarketIndices();

	Optional<ExchangeRateSnapshot> findExchangeRate(String currency);

	Optional<MarketForeignNetFlowSummary> findLatestForeignNetFlow();

	List<MarketDailyPrice> findDailyPrices(
		UUID securityId,
		LocalDate from,
		LocalDate to,
		int limit
	);
}
