package com.kmarket.navigator.backend.news.application.port;

import java.util.List;

import com.kmarket.navigator.backend.news.domain.CollectedNewsArticle;

public interface NewsProviderGateway {

	boolean configured();

	List<CollectedNewsArticle> search(String query, int display);
}
