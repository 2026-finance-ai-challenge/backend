package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;

public record RelatedStock(
	String stockCode,
	String nameKo,
	String nameEn,
	String market,
	BigDecimal confidence
) {
}
