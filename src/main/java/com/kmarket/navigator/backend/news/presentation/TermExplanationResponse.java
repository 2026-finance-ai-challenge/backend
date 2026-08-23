package com.kmarket.navigator.backend.news.presentation;

import java.math.BigDecimal;
import java.util.List;

import com.kmarket.navigator.backend.news.domain.TermExplanation;

record TermExplanationResponse(
	String selectedText,
	String normalizedTerm,
	String definition,
	String contextualMeaning,
	List<Source> sources,
	BigDecimal confidence,
	boolean reviewRequired,
	boolean sufficientEvidence,
	String refusalReason,
	String model,
	String promptVersion
) {
	static TermExplanationResponse from(TermExplanation explanation) {
		return new TermExplanationResponse(
			explanation.selectedText(), explanation.normalizedTerm(), explanation.definition(),
			explanation.contextualMeaning(), explanation.sources().stream()
				.map(source -> new Source(
					source.id(), source.title(), source.sourceName(), source.sourceUrl()
				))
				.toList(),
			explanation.confidence(), explanation.reviewRequired(),
			explanation.sufficientEvidence(), explanation.refusalReason(),
			explanation.model(), explanation.promptVersion()
		);
	}

	record Source(String id, String title, String sourceName, String sourceUrl) {
	}
}
