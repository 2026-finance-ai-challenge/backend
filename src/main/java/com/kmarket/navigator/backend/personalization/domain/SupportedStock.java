package com.kmarket.navigator.backend.personalization.domain;

import java.util.UUID;

public record SupportedStock(
	UUID securityId,
	String stockCode,
	String nameKo,
	String nameEn,
	String market
) {
}
