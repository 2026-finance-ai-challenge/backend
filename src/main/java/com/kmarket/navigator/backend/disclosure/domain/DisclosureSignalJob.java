package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;

public record DisclosureSignalJob(
	String receiptNumber,
	String title,
	List<String> paragraphs,
	List<String> candidateCompanies,
	int attempts
) {
	public DisclosureSignalJob {
		paragraphs = List.copyOf(paragraphs);
		candidateCompanies = List.copyOf(candidateCompanies);
	}
}
