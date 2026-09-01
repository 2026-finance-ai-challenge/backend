package com.kmarket.navigator.backend.tax.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

class TaxDocumentVerificationPolicyTests {

	@Test
	void individualVerificationDoesNotApplyCrossDocumentFindings() {
		TaxDocumentFields fields = new TaxDocumentFields(
			"Maria L. Chen",
			"US",
			"2026-01-12",
			null,
			"Internal Revenue Service",
			"000371",
			null,
			null,
			null
		);
		TaxDocument document = document(fields);
		TaxDocumentVerification generated = new TaxDocumentVerification(
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			TaxDocumentStatus.VERIFIED,
			fields,
			List.of(),
			List.of(new TaxDocumentIssue(
				"CROSS_DOCUMENT_MISMATCH",
				"HIGH",
				"This finding requires the explicit comparison flow."
			)),
			new BigDecimal("0.9000"),
			new BigDecimal("0.1000"),
			false,
			"tax-model",
			"v1"
		);

		TaxDocumentVerification result = new TaxDocumentVerificationPolicy()
			.validate(document, generated);

		assertThat(result.status()).isEqualTo(TaxDocumentStatus.VERIFIED);
		assertThat(result.issues()).noneMatch(issue ->
			"CROSS_DOCUMENT_MISMATCH".equals(issue.code())
		);
	}

	private TaxDocument document(TaxDocumentFields fields) {
		Instant now = Instant.parse("2026-01-12T00:00:00Z");
		return new TaxDocument(
			UUID.randomUUID(),
			UUID.randomUUID(),
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			"US",
			InvestorType.INDIVIDUAL,
			"residency.png",
			"image/png",
			1024,
			"sha256",
			"storage-key",
			TaxDocumentStatus.PROCESSING,
			45,
			"OCR",
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			fields,
			List.of(),
			List.of(),
			null,
			null,
			false,
			null,
			null,
			null,
			1,
			null,
			now,
			now,
			null
		);
	}
}
