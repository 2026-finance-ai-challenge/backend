package com.kmarket.navigator.backend.disclosure.presentation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsight;

record DisclosureInsightResponse(
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
	static DisclosureInsightResponse from(DisclosureInsight insight) {
		return new DisclosureInsightResponse(
			insight.receiptNumber(),
			insight.contentVersionHash(),
			insight.what(),
			insight.why(),
			insight.impact(),
			insight.sourceSectionIds(),
			insight.sufficientEvidence(),
			insight.refusalReason(),
			insight.modelId(),
			insight.promptVersion(),
			insight.generatedAt(),
			insight.whatKo(),
			insight.whyKo(),
			insight.impactKo()
		);
	}
}
