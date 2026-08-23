package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;

public final class NewsRanks {

	private NewsRanks() {
	}

	public static BigDecimal rank(NewsArticle article, NewsSort sort) {
		return switch (sort) {
			case LATEST -> BigDecimal.ZERO;
			case IMPORTANCE -> article.importance() == null
				? BigDecimal.ZERO
				: BigDecimal.valueOf(switch (article.importance()) {
					case CRITICAL -> 4;
					case HIGH -> 3;
					case MEDIUM -> 2;
					case LOW -> 1;
				});
			case MARKET_IMPACT -> article.marketImpactConfidence() == null
				? BigDecimal.ZERO
				: article.marketImpactConfidence();
		};
	}
}
