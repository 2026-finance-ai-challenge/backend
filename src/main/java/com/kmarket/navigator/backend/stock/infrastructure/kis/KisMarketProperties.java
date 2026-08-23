package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kmarket.market.kis")
public class KisMarketProperties {

	private boolean enabled;
	private URI baseUrl = URI.create("https://openapi.koreainvestment.com:9443");
	private String appKey = "";
	private String appSecret = "";
	private Duration connectTimeout = Duration.ofSeconds(3);
	private Duration readTimeout = Duration.ofSeconds(5);
	private Duration collectionDelay = Duration.ofMillis(125);
	private String redisKeyPrefix = "kmarket:market:kis";

	public boolean configured() {
		return enabled && !appKey.isBlank() && !appSecret.isBlank();
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

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getAppSecret() {
		return appSecret;
	}

	public void setAppSecret(String appSecret) {
		this.appSecret = appSecret;
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

	public Duration getCollectionDelay() {
		return collectionDelay;
	}

	public void setCollectionDelay(Duration collectionDelay) {
		this.collectionDelay = collectionDelay;
	}

	public String getRedisKeyPrefix() {
		return redisKeyPrefix;
	}

	public void setRedisKeyPrefix(String redisKeyPrefix) {
		this.redisKeyPrefix = redisKeyPrefix;
	}
}
