package com.kmarket.navigator.backend.stock.domain;

public record StockMarketView(
	StockIdentity stock,
	MarketQuoteSnapshot quote,
	ForeignOwnershipSnapshot foreignOwnership
) {
}
