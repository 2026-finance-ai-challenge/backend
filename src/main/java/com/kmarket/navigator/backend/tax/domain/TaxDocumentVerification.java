package com.kmarket.navigator.backend.tax.domain;

import java.math.BigDecimal;
import java.util.List;

public record TaxDocumentVerification(
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
	public TaxDocumentVerification {
		missingRequiredFields = List.copyOf(missingRequiredFields);
		issues = List.copyOf(issues);
	}
}
