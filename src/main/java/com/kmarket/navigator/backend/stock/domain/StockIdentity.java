package com.kmarket.navigator.backend.stock.domain;

import java.util.UUID;

public record StockIdentity(
	UUID securityId,
	String stockCode,
	String nameKo,
	String nameEn,
	String market,
	String sector,
	boolean watchlisted
) {
}
