package com.kmarket.navigator.backend.news.application.port;

import java.util.List;

import com.kmarket.navigator.backend.news.domain.NewsAnalysis;
import com.kmarket.navigator.backend.news.domain.TermExplanation;
import com.kmarket.navigator.backend.news.domain.TermReference;

public interface NewsAiGateway {

	default NewsAnalysis analyze(String title, List<String> paragraphs, List<String> candidateCompanies) {
		return analyze(title, paragraphs, candidateCompanies, "NEWS");
	}

	NewsAnalysis analyze(
		String title,
		List<String> paragraphs,
		List<String> candidateCompanies,
		String sourceType
	);

	TermExplanation explainTerm(
		String selectedText,
		String articleContext,
		List<TermReference> evidence,
		String safetyIdentifier
	);
}
