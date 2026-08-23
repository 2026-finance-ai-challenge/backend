package com.kmarket.navigator.backend.tax.application.port;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
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
}
