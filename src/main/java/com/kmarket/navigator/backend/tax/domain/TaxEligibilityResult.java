package com.kmarket.navigator.backend.tax.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.kmarket.navigator.backend.identity.domain.InvestorType;

public record TaxEligibilityResult(
	String countryCode,
	String countryName,
	InvestorType investorType,
	boolean treatyDataAvailable,
	BigDecimal domesticDefaultRate,
	BigDecimal treatyDividendRate,
	BigDecimal potentialQualifyingCorporateRate,
	BigDecimal minimumOwnershipPercent,
	LocalDate asOf,
	String sourceUrl,
	String domesticSourceUrl,
	List<String> requiredDocuments,
	List<String> caveats
) {
	public TaxEligibilityResult {
		requiredDocuments = List.copyOf(requiredDocuments);
		caveats = List.copyOf(caveats);
	}
}
