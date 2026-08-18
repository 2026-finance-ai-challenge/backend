package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.LocalDate;

import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

public record OpenDartFiling(
	String receiptNumber,
	String corpCode,
	String corporationName,
	String stockCode,
	CorporationClass corporationClass,
	DisclosureType disclosureType,
	String reportName,
	String submitter,
	LocalDate filedDate,
	String remark
) {
}
