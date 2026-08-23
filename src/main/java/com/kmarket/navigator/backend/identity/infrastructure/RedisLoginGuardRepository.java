package com.kmarket.navigator.backend.identity.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.kmarket.navigator.backend.identity.application.port.LoginGuardRepository;

@Repository
public class RedisLoginGuardRepository implements LoginGuardRepository {

	private static final long FAILURE_LIMIT = 5;
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
		local count = redis.call('INCR', KEYS[1])
		if count == 1 then
		  redis.call('PEXPIRE', KEYS[1], ARGV[1])
		end
		return count
		""", Long.class);

	private final StringRedisTemplate template;
	private final String prefix;

	public RedisLoginGuardRepository(StringRedisTemplate template, IdentityProperties properties) {
		this.template = template;
		this.prefix = properties.redisKeyPrefix();
	}

	@Override
	public Optional<Duration> retryAfter(String guardKey, Instant now) {
		String key = key(guardKey);
		String failures = template.opsForValue().get(key);
		if (failures == null || Long.parseLong(failures) < FAILURE_LIMIT) {
			return Optional.empty();
		}
		Long seconds = template.getExpire(key, TimeUnit.SECONDS);
		return seconds == null || seconds < 0
			? Optional.of(WINDOW)
			: Optional.of(Duration.ofSeconds(seconds));
	}

	@Override
	public void recordFailure(String guardKey, Instant now) {
		template.execute(
			FAILURE_SCRIPT,
			List.of(key(guardKey)),
			Long.toString(WINDOW.toMillis())
		);
	}

	@Override
	public void clear(String guardKey) {
		template.delete(key(guardKey));
	}

	private String key(String guardKey) {
		return prefix + ":login-guard:" + guardKey;
	}
}
