package com.kmarket.navigator.backend.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatRoom(
	UUID id,
	UUID userId,
	String name,
	ChatContext context,
	long version,
	Instant createdAt,
	Instant updatedAt,
	Instant lastMessageAt
) {
}
