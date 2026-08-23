package com.kmarket.navigator.backend.news.domain;

import java.util.List;
import java.util.UUID;

public record NewsAnalysisJob(
	UUID articleId,
	String title,
	List<String> paragraphs,
	List<String> candidateCompanies,
	int attempts
) {
	public NewsAnalysisJob {
		paragraphs = List.copyOf(paragraphs);
		candidateCompanies = List.copyOf(candidateCompanies);
	}
}
