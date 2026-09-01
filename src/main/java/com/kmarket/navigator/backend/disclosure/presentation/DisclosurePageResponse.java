package com.kmarket.navigator.backend.disclosure.presentation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.kmarket.navigator.backend.disclosure.domain.DisclosurePage;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.DocumentStatus;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;

record DisclosurePageResponse(List<Item> items, String nextCursor) {

	static DisclosurePageResponse from(DisclosurePage page) {
		return new DisclosurePageResponse(page.items().stream().map(Item::from).toList(), page.nextCursor());
	}

	record Item(
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
		private static Item from(DisclosureSummary summary) {
			return new Item(
				summary.receiptNumber(),
				summary.corpCode(),
				summary.issuerNameKo(),
				summary.issuerNameEn(),
				summary.stockCode(),
				summary.market(),
				summary.type(),
				summary.titleKo(),
				summary.titleEn(),
				summary.eventType(),
				summary.sentiment(),
				summary.importance(),
				summary.marketImpact(),
				summary.filedDate(),
				summary.filedDateTotal(),
				summary.detectedAt(),
				summary.correction(),
				summary.documentStatus(),
				summary.indexStatus(),
				summary.officialUrl()
			);
		}
	}
}
