package com.kmarket.navigator.backend.identity.application.port;

import java.time.Instant;
import java.util.UUID;

import com.kmarket.navigator.backend.identity.application.ClientContext;

public interface SecurityAuditRepository {
	void record(
		UUID userId,
		String eventType,
		String subjectType,
		String subjectId,
		ClientContext context,
		Instant createdAt
	);
}
