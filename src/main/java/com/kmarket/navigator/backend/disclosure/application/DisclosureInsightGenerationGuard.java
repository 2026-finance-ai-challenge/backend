package com.kmarket.navigator.backend.disclosure.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
class DisclosureInsightGenerationGuard {

	private static final Duration LOCK_TTL = Duration.ofMinutes(3);
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
		  return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);
	private final StringRedisTemplate redisTemplate;

	DisclosureInsightGenerationGuard(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	Guard tryAcquire(String contentVersion) {
		String key = "kmarket:disclosure-insight:lock:" + contentVersion;
		String token = UUID.randomUUID().toString();
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL);
		return Boolean.TRUE.equals(acquired) ? new Guard(key, token) : null;
	}

	final class Guard implements AutoCloseable {

		private final String key;
		private final String token;

		private Guard(String key, String token) {
			this.key = key;
			this.token = token;
		}

		@Override
		public void close() {
			redisTemplate.execute(RELEASE, List.of(key), token);
		}
	}
}
