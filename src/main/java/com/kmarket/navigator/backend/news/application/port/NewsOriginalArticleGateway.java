package com.kmarket.navigator.backend.news.application.port;

import java.util.Optional;

import com.kmarket.navigator.backend.news.domain.OriginalNewsArticle;

public interface NewsOriginalArticleGateway {

	Optional<OriginalNewsArticle> fetch(String originalUrl);
}
