package com.kmarket.navigator.backend.chat.domain;

import java.time.Instant;

public record AgentEvidence(
	String id,
	String title,
	String content,
	String source,
	Instant asOf,
	String referenceId,
	String url
) {
}
