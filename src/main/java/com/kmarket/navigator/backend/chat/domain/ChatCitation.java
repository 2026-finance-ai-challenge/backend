package com.kmarket.navigator.backend.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatCitation(
	String id,
	String sourceType,
	String referenceId,
	String title,
	String excerpt,
	String url,
	Instant asOf,
	List<UUID> sectionIds
) {
	public ChatCitation {
		sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
	}
}
