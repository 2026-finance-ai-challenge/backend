package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;

public record DisclosureInsightGeneration(
	String what,
	String why,
	String impact,
	List<String> evidenceIds,
	boolean sufficientEvidence,
	String refusalReason,
	String modelId,
	String promptVersion,
	String whatKo,
	String whyKo,
	String impactKo
) {
	public DisclosureInsightGeneration {
		evidenceIds = List.copyOf(evidenceIds);
	}
}
