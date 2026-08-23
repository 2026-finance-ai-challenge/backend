package com.kmarket.navigator.backend.news.domain;

import java.math.BigDecimal;
import java.util.List;

public record TermExplanation(
	String selectedText,
	String normalizedTerm,
	String definition,
	String contextualMeaning,
	List<TermReference> sources,
	BigDecimal confidence,
	boolean reviewRequired,
	boolean sufficientEvidence,
	String refusalReason,
	String model,
	String promptVersion
) {
	public TermExplanation {
		sources = List.copyOf(sources);
	}
}
