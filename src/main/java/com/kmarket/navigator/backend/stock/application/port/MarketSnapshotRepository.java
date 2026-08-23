package com.kmarket.navigator.backend.stock.application.port;

import java.util.List;

import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;
import com.kmarket.navigator.backend.stock.domain.MarketQuoteSnapshot;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;
import com.kmarket.navigator.backend.stock.domain.ForeignOwnershipSnapshot;

public interface MarketSnapshotRepository {

	List<String> findSupportedStockCodes();

	void saveQuote(String stockCode, MarketQuoteSnapshot quote);

	void saveIndex(MarketIndexSnapshot index);

	List<ForeignLimitCollectionTarget> findForeignLimitTargets();

	void saveForeignOwnership(String stockCode, ForeignOwnershipSnapshot snapshot);
}
