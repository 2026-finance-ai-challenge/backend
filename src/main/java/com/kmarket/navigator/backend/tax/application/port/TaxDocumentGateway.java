package com.kmarket.navigator.backend.tax.application.port;

import java.util.List;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentReviewInput;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

public interface TaxDocumentGateway {

	TaxDocumentVerification verify(
		TaxDocumentType documentType,
		String fileName,
		String mediaType,
		byte[] content,
		String expectedResidencyCountry,
		InvestorType investorType,
		String safetyIdentifier
	);

	TaxDocumentComparison compare(
		List<TaxDocumentReviewInput> documents,
		String expectedResidencyCountry,
		InvestorType investorType,
		String safetyIdentifier
	);
}
