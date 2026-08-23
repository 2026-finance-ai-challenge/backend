package com.kmarket.navigator.backend.personalization.domain;

import java.time.Instant;
import java.util.UUID;

public record UserNotification(
	UUID id,
	String notificationType,
	String title,
	String body,
	String referenceType,
	String referenceId,
	Instant createdAt,
	Instant readAt
) {
}
