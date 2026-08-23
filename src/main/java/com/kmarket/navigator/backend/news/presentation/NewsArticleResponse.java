package com.kmarket.navigator.backend.news.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisStatus;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsContentAvailability;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;
import com.kmarket.navigator.backend.news.domain.RelatedStock;

record NewsArticleResponse(
	UUID id,
	UUID clusterId,
	String originalTitle,
	String originalExcerpt,
	String originalBody,
	String englishTitle,
	String englishBody,
	String what,
	String why,
	String impact,
	String eventType,
	NewsSentiment sentiment,
	NewsImportance importance,
	MarketImpact marketImpact,
	NewsImportance marketImpactImportance,
	BigDecimal marketImpactScore,
	Confidence confidence,
	String originalUrl,
	String publisher,
	String thumbnailUrl,
	NewsContentAvailability contentAvailability,
	NewsAnalysisStatus analysisStatus,
	String modelId,
	String promptVersion,
	Instant publishedAt,
	Instant collectedAt,
	Instant analyzedAt,
	long relatedCoverageCount,
	List<RelatedStock> relatedStocks
) {
	static NewsArticleResponse from(NewsArticle article) {
		return new NewsArticleResponse(
			article.id(), article.clusterId(), article.originalTitle(), article.originalExcerpt(),
			article.originalBody(), article.englishTitle(), article.englishBody(), article.what(),
			article.why(), article.impact(), article.eventType(), article.sentiment(),
			article.importance(), article.marketImpact(), article.marketImpactImportance(),
			article.marketImpactScore(), new Confidence(
				article.eventConfidence(), article.sentimentConfidence(),
				article.importanceConfidence(), article.marketImpactConfidence()
			), article.originalUrl(), article.publisher(), article.thumbnailUrl(),
			article.contentAvailability(), article.analysisStatus(), article.modelId(),
			article.promptVersion(), article.publishedAt(), article.collectedAt(),
			article.analyzedAt(), article.relatedCoverageCount(), article.relatedStocks()
		);
	}

	record Confidence(
		BigDecimal event,
		BigDecimal sentiment,
		BigDecimal importance,
		BigDecimal marketImpact
	) {
	}
}
