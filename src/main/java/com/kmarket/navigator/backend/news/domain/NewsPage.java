package com.kmarket.navigator.backend.news.domain;

import java.util.List;

public record NewsPage(List<NewsArticle> items, String nextCursor) {
	public NewsPage {
		items = List.copyOf(items);
	}
}
