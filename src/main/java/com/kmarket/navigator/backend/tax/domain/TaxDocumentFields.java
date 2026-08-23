package com.kmarket.navigator.backend.tax.domain;

public record TaxDocumentFields(
	String holderName,
	String residencyCountry,
	String issueDate,
	String expiryDate,
	String issuingAuthority,
	String documentNumber,
	String apostilleCountry,
	String treatyCountry,
	String investorType
) {
}
