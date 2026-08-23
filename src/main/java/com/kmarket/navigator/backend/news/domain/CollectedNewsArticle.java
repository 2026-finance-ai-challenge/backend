package com.kmarket.navigator.backend.news.domain;

import java.time.Instant;

public record CollectedNewsArticle(
	String providerArticleId,
	String title,
	String excerpt,
	String originalUrl,
	String canonicalUrl,
	String publisher,
	String thumbnailUrl,
	Instant publishedAt
) {
}
