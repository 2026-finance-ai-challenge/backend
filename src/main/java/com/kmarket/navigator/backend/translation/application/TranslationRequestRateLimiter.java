package com.kmarket.navigator.backend.translation.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Component
class TranslationRequestRateLimiter {

	private final StringRedisTemplate redisTemplate;
	private final int perClientMinute;
	private final int globalDaily;

	TranslationRequestRateLimiter(
		StringRedisTemplate redisTemplate,
		@Value("${kmarket.translation.per-client-minute-limit:12}") int perClientMinute,
		@Value("${kmarket.translation.global-daily-limit:5000}") int globalDaily
	) {
		this.redisTemplate = redisTemplate;
		this.perClientMinute = perClientMinute;
		this.globalDaily = globalDaily;
	}

	void check(String clientHash) {
		long minute = System.currentTimeMillis() / 60_000L;
		increment("kmarket:translation:rate:client:" + clientHash + ":" + minute,
			perClientMinute, Duration.ofMinutes(2));
		String date = LocalDate.now(ZoneOffset.UTC).toString();
		increment("kmarket:translation:rate:global:" + date, globalDaily, Duration.ofDays(2));
	}

	private void increment(String key, int limit, Duration ttl) {
		Long count = redisTemplate.opsForValue().increment(key);
		if (count != null && count == 1L) {
			redisTemplate.expire(key, ttl);
		}
		if (count == null || count > limit) {
			throw new BusinessException(ErrorCode.AI_RATE_LIMITED, java.util.Map.of(
				"retryAfterSeconds", 60
			));
		}
	}
}
