package com.kmarket.navigator.backend.news.domain;

public record TermReference(
	String id,
	String title,
	String content,
	String sourceName,
	String sourceUrl
) {
}
