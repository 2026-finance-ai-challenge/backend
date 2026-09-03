package com.kmarket.navigator.backend.translation.application;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TranslationRequestRateLimiterTests {

	@Test
	@SuppressWarnings("unchecked")
	void sharedCacheUsesClientLimitButNotGenerationBudget() {
		var redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.increment(anyString(), anyLong())).thenReturn(1L);
		var limiter = new TranslationRequestRateLimiter(redis, 12, 5000);
		limiter.checkBatch("client", 0);
		verify(values).increment(startsWith("kmarket:translation:rate:client:"), eq(1L));
		verify(values, never()).increment(startsWith("kmarket:translation:rate:global:"), anyLong());
		limiter.checkBatch("client", 3);
		verify(values).increment(startsWith("kmarket:translation:rate:global:"), eq(3L));
	}
}
