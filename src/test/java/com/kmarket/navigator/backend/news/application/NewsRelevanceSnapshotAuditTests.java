package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "NEWS_RELEVANCE_SNAPSHOT", matches = ".+")
class NewsRelevanceSnapshotAuditTests {
	@Test
	void auditsStoredAssociationsUsingProductionMatcherWithoutWrites() throws Exception {
		var mapper = JsonMapper.builder().build();
		var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("NEWS_RELEVANCE_SNAPSHOT"))));
		var stocks = new ArrayList<NewsStockMapping>();
		for (var stock : snapshot.path("stocks")) stocks.add(mapper.treeToValue(stock, NewsStockMapping.class));
		var matcher = new NewsStockMatcher();
		int accepted = 0;
		int rejectedArticles = 0;
		int rejectedLinks = 0;
		var examples = new ArrayList<String>();
		for (var article : snapshot.path("articles")) {
			var verified = matcher.verifiedArticleMatches(article.path("title").asString(), article.path("body").asString(), stocks);
			if (verified.isEmpty()) rejectedArticles++; else accepted++;
			List<String> unsupported = new ArrayList<>();
			for (var stock : article.path("stocks")) if (!verified.containsKey(stock.asString())) unsupported.add(stock.asString());
			if (!unsupported.isEmpty()) {
				rejectedLinks += unsupported.size();
				examples.add(article.path("id").asString() + " " + unsupported + " " + article.path("title").asString());
			}
		}
		System.out.printf("NEWS_RELEVANCE_AUDIT at=%s stocks=%d total=%d accepted=%d rejectedArticles=%d rejectedLinks=%d%n",
			snapshot.path("auditedAt").asString(), stocks.size(), snapshot.path("articles").size(), accepted, rejectedArticles, rejectedLinks);
		examples.forEach(example -> System.out.println("REVIEW " + example));
		assertThat(stocks).hasSize(75);
		assertThat(snapshot.path("articles").size()).isPositive();
	}
}
