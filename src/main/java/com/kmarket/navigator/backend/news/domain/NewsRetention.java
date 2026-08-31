package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record NewsRetention(
	UUID articleId,
	UUID clusterId,
	String signatureHash,
	String normalizedTitle,
	Map<String, BigDecimal> stockConfidences
) {
	public NewsRetention {
		stockConfidences = Map.copyOf(stockConfidences);
	}
}
