package com.kmarket.navigator.backend.tax.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Component
public class TaxUploadRateLimiter {

	private static final long LIMIT = 10;
	private static final Duration WINDOW = Duration.ofHours(1);
	private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
		local count = redis.call('INCR', KEYS[1])
		if count == 1 then
		  redis.call('PEXPIRE', KEYS[1], ARGV[1])
		end
		return count
		""", Long.class);
	private final StringRedisTemplate redisTemplate;

	public TaxUploadRateLimiter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public void check(UUID userId) {
		Long count = redisTemplate.execute(
			SCRIPT,
			List.of("kmarket:tax:upload:" + userId),
			Long.toString(WINDOW.toMillis())
		);
		if (count != null && count > LIMIT) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_RATE_LIMITED);
		}
	}
}
