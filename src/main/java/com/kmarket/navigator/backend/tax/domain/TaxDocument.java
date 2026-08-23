package com.kmarket.navigator.backend.tax.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.identity.domain.InvestorType;

public record TaxDocument(
	UUID id,
	UUID userId,
	TaxDocumentType documentType,
	String expectedResidencyCountry,
	InvestorType investorType,
	String originalFileName,
	String mediaType,
	long sizeBytes,
	String sha256,
	String storageKey,
	TaxDocumentStatus status,
	int progress,
	String stage,
	TaxDocumentType detectedDocumentType,
	TaxDocumentFields fields,
	List<String> missingRequiredFields,
	List<TaxDocumentIssue> issues,
	BigDecimal ocrConfidence,
	BigDecimal tamperRisk,
	boolean manualReviewRequired,
	String modelId,
	String promptVersion,
	String requestId,
	int attempts,
	String errorCode,
	Instant createdAt,
	Instant updatedAt,
	Instant deletedAt
) {
	public TaxDocument {
		missingRequiredFields = List.copyOf(missingRequiredFields);
		issues = List.copyOf(issues);
	}
}
