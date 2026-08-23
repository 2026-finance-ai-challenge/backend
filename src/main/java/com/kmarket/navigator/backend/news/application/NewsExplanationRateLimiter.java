package com.kmarket.navigator.backend.news.application;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Component
public class NewsExplanationRateLimiter {

	private static final long LIMIT = 20;
	private static final Duration WINDOW = Duration.ofHours(1);
	private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
		local count = redis.call('INCR', KEYS[1])
		if count == 1 then
		  redis.call('PEXPIRE', KEYS[1], ARGV[1])
		end
		return count
		""", Long.class);
	private final StringRedisTemplate redisTemplate;

	public NewsExplanationRateLimiter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void check(String clientIpHash) {
		Long count = redisTemplate.execute(
			SCRIPT,
			List.of("kmarket:news:explain:" + clientIpHash),
			Long.toString(WINDOW.toMillis())
		);
		if (count != null && count > LIMIT) {
			throw new BusinessException(ErrorCode.AI_RATE_LIMITED);
		}
	}
}
