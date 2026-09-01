package com.kmarket.navigator.backend.stock.domain;

public record ForeignOwnershipCollectionTarget(
	String stockCode,
	String isinCode,
	boolean subjectToAcquisitionLimit
) {
}
