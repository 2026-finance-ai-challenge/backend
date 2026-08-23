package com.kmarket.navigator.backend.news.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.news.domain.NewsStockMapping;

@Component
public class NewsStockMatcher {

	public Map<String, BigDecimal> match(
		String text,
		Iterable<NewsStockMapping> mappings,
		String queryStockCode
	) {
		String lower = text.toLowerCase(Locale.ROOT);
		Map<String, BigDecimal> matches = new LinkedHashMap<>();
		for (NewsStockMapping mapping : mappings) {
			BigDecimal confidence = null;
			if (lower.contains(mapping.stockCode().toLowerCase(Locale.ROOT))) {
				confidence = new BigDecimal("0.99");
			} else if (contains(lower, mapping.nameKo()) || contains(lower, mapping.nameEn())) {
				confidence = new BigDecimal("0.95");
			} else if (mapping.aliases().stream().anyMatch(alias -> contains(lower, alias))) {
				confidence = new BigDecimal("0.90");
			} else if (mapping.stockCode().equals(queryStockCode)) {
				confidence = new BigDecimal("0.65");
			}
			if (confidence != null) {
				matches.put(mapping.stockCode(), confidence);
			}
		}
		return Map.copyOf(matches);
	}

	private boolean contains(String lower, String candidate) {
		return candidate != null
			&& candidate.length() >= 2
			&& lower.contains(candidate.toLowerCase(Locale.ROOT));
	}
}
