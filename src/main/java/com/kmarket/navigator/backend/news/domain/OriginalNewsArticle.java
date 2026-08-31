package com.kmarket.navigator.backend.news.domain;

public record OriginalNewsArticle(
	String body,
	String canonicalUrl,
	String thumbnailUrl,
	String sourcePolicy
) {
}
