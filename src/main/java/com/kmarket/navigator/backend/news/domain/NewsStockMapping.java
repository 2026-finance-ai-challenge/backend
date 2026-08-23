package com.kmarket.navigator.backend.news.domain;

import java.util.List;

public record NewsStockMapping(
	String stockCode,
	String nameKo,
	String nameEn,
	String market,
	List<String> aliases
) {
	public NewsStockMapping {
		aliases = List.copyOf(aliases);
	}
}
