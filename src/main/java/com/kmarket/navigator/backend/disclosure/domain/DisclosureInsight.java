package com.kmarket.navigator.backend.disclosure.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DisclosureInsight(
	String receiptNumber,
	String contentVersionHash,
	String what,
	String why,
	String impact,
	List<UUID> sourceSectionIds,
	boolean sufficientEvidence,
	String refusalReason,
	String modelId,
	String promptVersion,
	Instant generatedAt,
	String whatKo,
	String whyKo,
	String impactKo
) {
	public DisclosureInsight {
		sourceSectionIds = List.copyOf(sourceSectionIds);
	}
}
