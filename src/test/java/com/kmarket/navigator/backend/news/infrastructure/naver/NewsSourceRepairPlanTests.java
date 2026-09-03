package com.kmarket.navigator.backend.news.infrastructure.naver;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kmarket.navigator.backend.global.concurrent.BoundedTasks;
import com.kmarket.navigator.backend.news.application.NewsFingerprint;
import com.kmarket.navigator.backend.news.application.NewsStockMatcher;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "NEWS_SOURCE_REPAIR_SNAPSHOT", matches = ".+")
class NewsSourceRepairPlanTests {
	@Test
	void buildsReversibleRepairPlanFromPublicOriginalsWithoutDatabaseWrites() throws Exception {
		var mapper = JsonMapper.builder().build();
		var snapshotPath = Path.of(System.getenv("NEWS_SOURCE_REPAIR_SNAPSHOT"));
		var snapshot = mapper.readTree(Files.readString(snapshotPath));
		var stocks = new ArrayList<NewsStockMapping>();
		for (var stock : snapshot.path("stocks")) stocks.add(mapper.treeToValue(stock, NewsStockMapping.class));
		var matcher = new NewsStockMatcher();
		var fingerprint = new NewsFingerprint();
		var gateway = new PublisherOriginalArticleGateway(HttpClient.newHttpClient(), Duration.ofSeconds(15));
		var plan = new ConcurrentLinkedQueue<tools.jackson.databind.node.ObjectNode>();
		var articles = new ArrayList<tools.jackson.databind.JsonNode>();
		snapshot.path("articles").forEach(articles::add);
		BoundedTasks.forEach(articles, 4, article -> {
			String oldBody = article.path("body").asString("");
			var matches = matcher.verifiedArticleMatches(article.path("title").asString(), oldBody, stocks);
			Set<String> oldStocks = new java.util.HashSet<>();
			article.path("stocks").forEach(stock -> oldStocks.add(stock.asString()));
			if (oldBody.isBlank() && oldStocks.isEmpty()) return;
			if (!matches.isEmpty() && matches.keySet().equals(oldStocks)) return;
			String body = oldBody;
			String policy = null;
			if (matches.isEmpty()) {
				var fetched = gateway.fetch(article.path("originalUrl").asString());
				if (fetched.isPresent()) {
					body = fetched.get().body();
					policy = fetched.get().sourcePolicy();
					matches = matcher.verifiedArticleMatches(article.path("title").asString(), body, stocks);
				}
			}
			var item = mapper.createObjectNode();
			item.put("id", article.path("id").asString());
			item.put("title", article.path("title").asString());
			item.put("previousBodyHash", fingerprint.sha256(oldBody));
			item.set("previousStocks", article.path("stocks"));
			item.put("action", matches.isEmpty() ? "QUARANTINE" : body.equals(oldBody) ? "REMAP" : "RESTORE");
			item.put("body", matches.isEmpty() ? "" : body);
			item.put("sourcePolicy", policy);
			var verified = item.putArray("stocks");
			matches.keySet().stream().sorted().forEach(verified::add);
			plan.add(item);
		});
		var report = mapper.createObjectNode();
		report.put("snapshotAt", snapshot.path("auditedAt").asString());
		report.put("databaseWrites", false);
		report.set("items", mapper.valueToTree(plan.stream().sorted(java.util.Comparator.comparing(item -> item.path("id").asString())).toList()));
		var output = snapshotPath.resolveSibling("news-source-repair-plan.json");
		Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
		for (var action : List.of("REMAP", "RESTORE", "QUARANTINE")) System.out.printf("NEWS_REPAIR_PLAN %s=%d%n", action, plan.stream().filter(item -> item.path("action").asString().equals(action)).count());
		System.out.printf("NEWS_REPAIR_PLAN output=%s writes=false%n", output);
		assertThat(articles).isNotEmpty();
	}
}
