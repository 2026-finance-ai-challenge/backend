package com.kmarket.navigator.backend.tax.domain;

import java.math.BigDecimal;
import java.util.List;

public record TaxDocumentReviewInput(
	TaxDocumentType documentType,
	TaxDocumentType detectedDocumentType,
	TaxDocumentStatus status,
	TaxDocumentFields fields,
	List<String> missingRequiredFields,
	List<TaxDocumentIssue> issues,
	BigDecimal ocrConfidence,
	BigDecimal tamperRisk,
	boolean manualReviewRequired,
	String modelId,
	String promptVersion
) {
	public TaxDocumentReviewInput {
		missingRequiredFields = List.copyOf(missingRequiredFields);
		issues = List.copyOf(issues);
	}
}
