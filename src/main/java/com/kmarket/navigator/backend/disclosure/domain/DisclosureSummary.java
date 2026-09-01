package com.kmarket.navigator.backend.disclosure.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;

public record DisclosureSummary(
	String receiptNumber,
	String corpCode,
	String issuerNameKo,
	String issuerNameEn,
	String stockCode,
	Market market,
	DisclosureType type,
	String titleKo,
	String titleEn,
	String eventType,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	LocalDate filedDate,
	long filedDateTotal,
	Instant detectedAt,
	boolean correction,
	DocumentStatus documentStatus,
	IndexStatus indexStatus,
	String officialUrl
) {
}
