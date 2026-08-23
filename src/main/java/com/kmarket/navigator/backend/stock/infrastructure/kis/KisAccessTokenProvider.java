package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

@Component
class KisAccessTokenProvider {

	private static final Duration LOCK_TTL = Duration.ofSeconds(10);
	private static final Duration WAIT_STEP = Duration.ofMillis(100);
	private static final int WAIT_ATTEMPTS = 20;
	private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
		  return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);

	private final RestClient restClient;
	private final StringRedisTemplate redis;
	private final KisMarketProperties properties;
	private final KisCircuitBreaker circuitBreaker;

	KisAccessTokenProvider(
		@Qualifier("kisMarketRestClient") RestClient restClient,
		StringRedisTemplate redis,
		KisMarketProperties properties,
		KisCircuitBreaker circuitBreaker
	) {
		this.restClient = restClient;
		this.redis = redis;
		this.properties = properties;
		this.circuitBreaker = circuitBreaker;
	}

	String accessToken() {
		String cached = redis.opsForValue().get(tokenKey());
		if (cached != null && !cached.isBlank()) {
			return cached;
		}
		String lockValue = UUID.randomUUID().toString();
		Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(), lockValue, LOCK_TTL);
		if (Boolean.TRUE.equals(acquired)) {
			try {
				String doubleChecked = redis.opsForValue().get(tokenKey());
				return doubleChecked == null || doubleChecked.isBlank()
					? issueToken()
					: doubleChecked;
			} finally {
				redis.execute(UNLOCK_SCRIPT, List.of(lockKey()), lockValue);
			}
		}
		for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
			LockSupport.parkNanos(WAIT_STEP.toNanos());
			String issued = redis.opsForValue().get(tokenKey());
			if (issued != null && !issued.isBlank()) {
				return issued;
			}
		}
		throw new KisProviderException("KIS access token issuance timed out");
	}

	private String issueToken() {
		JsonNode response = circuitBreaker.execute(() -> restClient.post()
			.uri("/oauth2/tokenP")
			.body(Map.of(
				"grant_type", "client_credentials",
				"appkey", properties.getAppKey(),
				"appsecret", properties.getAppSecret()
			))
			.retrieve()
			.body(JsonNode.class));
		String token = text(response, "access_token");
		if (token.isBlank()) {
			throw new KisProviderException("KIS access token response is invalid");
		}
		long expiresIn = longValue(response, "expires_in", 86_400L);
		Duration ttl = Duration.ofSeconds(Math.max(60, expiresIn - 60));
		redis.opsForValue().set(tokenKey(), token, ttl);
		return token;
	}

	private String tokenKey() {
		return properties.getRedisKeyPrefix() + ":access-token";
	}

	private String lockKey() {
		return properties.getRedisKeyPrefix() + ":access-token-lock";
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return "";
		}
		String value = node.path(field).stringValue();
		return value == null ? "" : value.trim();
	}

	private static long longValue(JsonNode node, String field, long fallback) {
		if (node == null) {
			return fallback;
		}
		try {
			String text = node.path(field).stringValue();
			return text == null ? node.path(field).longValue() : Long.parseLong(text);
		} catch (RuntimeException exception) {
			return fallback;
		}
	}
}
