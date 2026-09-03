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
	String investorType,
	String birthDate,
	String phoneNumber,
	String address,
	Integer previewVersion
) {
	public TaxDocumentFields(String holderName, String residencyCountry, String issueDate, String expiryDate,
		String issuingAuthority, String documentNumber, String apostilleCountry, String treatyCountry, String investorType) {
		this(holderName, residencyCountry, issueDate, expiryDate, issuingAuthority, documentNumber, apostilleCountry,
			treatyCountry, investorType, null, null, null, null);
	}
}
