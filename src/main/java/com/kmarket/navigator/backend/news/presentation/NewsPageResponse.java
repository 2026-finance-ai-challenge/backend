package com.kmarket.navigator.backend.news.presentation;

import java.util.List;

import com.kmarket.navigator.backend.news.domain.NewsPage;

record NewsPageResponse(List<NewsArticleResponse> items, String nextCursor) {
	static NewsPageResponse from(NewsPage page) {
		return new NewsPageResponse(
			page.items().stream().map(NewsArticleResponse::from).toList(),
			page.nextCursor()
		);
	}
}
