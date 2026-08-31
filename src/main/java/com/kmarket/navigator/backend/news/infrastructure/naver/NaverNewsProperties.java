package com.kmarket.navigator.backend.news.infrastructure.naver;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmarket.news.naver")
public class NaverNewsProperties {

	private boolean enabled;
	private URI baseUrl = URI.create("https://openapi.naver.com");
	private String clientId = "";
	private String clientSecret = "";
	private int display = 20;
	private int targetBatchSize = 75;
	private Duration connectTimeout = Duration.ofSeconds(10);
	private Duration readTimeout = Duration.ofSeconds(30);
	private Duration requestDelay = Duration.ofMillis(125);
	private Duration maxArticleAge = Duration.ofHours(1);
	private List<String> queries = new ArrayList<>();

	public boolean configured() {
		return enabled && !clientId.isBlank() && !clientSecret.isBlank();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public URI getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(URI baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public int getDisplay() {
		return display;
	}

	public void setDisplay(int display) {
		this.display = Math.max(1, Math.min(display, 100));
	}

	public int getTargetBatchSize() {
		return targetBatchSize;
	}

	public void setTargetBatchSize(int targetBatchSize) {
		this.targetBatchSize = Math.max(1, Math.min(targetBatchSize, 75));
	}

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getReadTimeout() {
		return readTimeout;
	}

	public void setReadTimeout(Duration readTimeout) {
		this.readTimeout = readTimeout;
	}

	public Duration getRequestDelay() {
		return requestDelay;
	}

	public void setRequestDelay(Duration requestDelay) {
		this.requestDelay = requestDelay;
	}

	public Duration getMaxArticleAge() {
		return maxArticleAge;
	}

	public void setMaxArticleAge(Duration maxArticleAge) {
		if (maxArticleAge == null || maxArticleAge.isNegative() || maxArticleAge.isZero()) {
			throw new IllegalArgumentException("maxArticleAge must be positive");
		}
		this.maxArticleAge = maxArticleAge;
	}

	public List<String> getQueries() {
		return List.copyOf(queries);
	}

	public void setQueries(List<String> queries) {
		this.queries = queries == null
			? new ArrayList<>()
			: queries.stream()
				.filter(query -> query != null && !query.isBlank())
				.map(String::strip)
				.distinct()
				.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
}
