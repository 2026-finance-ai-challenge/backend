package com.kmarket.navigator.backend.news.domain;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

public record NewsCursor(BigDecimal sortRank, Instant publishedAt, UUID id) {

	public String encode() {
		String value = sortRank.toPlainString() + ":" + publishedAt.toEpochMilli() + ":" + id;
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static NewsCursor decode(String cursor) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split(":", 3);
			if (parts.length != 3) {
				throw new IllegalArgumentException("invalid cursor");
			}
			return new NewsCursor(
				new BigDecimal(parts[0]),
				Instant.ofEpochMilli(Long.parseLong(parts[1])),
				UUID.fromString(parts[2])
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_CURSOR);
		}
	}
}
