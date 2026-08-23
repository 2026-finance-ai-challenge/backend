package com.kmarket.navigator.backend.stock.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Component
class GlobalPeerGenerationGuard {

	private static final Duration LOCK_TTL = Duration.ofSeconds(90);
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then
		  return redis.call('DEL', KEYS[1])
		end
		return 0
		""", Long.class);
	private final StringRedisTemplate redisTemplate;

	GlobalPeerGenerationGuard(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	Guard acquire(String stockCode) {
		String key = "kmarket:global-peer:lock:" + stockCode;
		String token = UUID.randomUUID().toString();
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL);
		if (!Boolean.TRUE.equals(acquired)) {
			throw new BusinessException(ErrorCode.GLOBAL_PEER_GENERATION_IN_PROGRESS);
		}
		return new Guard(key, token);
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
