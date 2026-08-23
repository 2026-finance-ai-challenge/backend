package com.kmarket.navigator.backend.personalization.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

public record NotificationCursor(Instant createdAt, UUID id) {

	public String encode() {
		String value = createdAt.toEpochMilli() + ":" + id;
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	public static NotificationCursor decode(String cursor) {
		try {
			String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = decoded.split(":", 2);
			if (parts.length != 2) {
				throw new IllegalArgumentException("invalid cursor");
			}
			return new NotificationCursor(
				Instant.ofEpochMilli(Long.parseLong(parts[0])),
				UUID.fromString(parts[1])
			);
		}
		catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_CURSOR);
		}
	}
}
