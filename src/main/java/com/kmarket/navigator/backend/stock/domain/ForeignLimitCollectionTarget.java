package com.kmarket.navigator.backend.stock.domain;

public record ForeignLimitCollectionTarget(
	String stockCode,
	String isinCode
) {
}
