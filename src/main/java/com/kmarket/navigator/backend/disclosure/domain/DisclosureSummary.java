package com.kmarket.navigator.backend.disclosure.domain;

import java.time.Instant;
import java.time.LocalDate;

public record DisclosureSummary(
	String receiptNumber,
	String corpCode,
	String issuerNameKo,
	String issuerNameEn,
	String stockCode,
	Market market,
	DisclosureType type,
	String titleKo,
	String titleEn,
	LocalDate filedDate,
	Instant detectedAt,
	boolean correction,
	DocumentStatus documentStatus,
	IndexStatus indexStatus,
	String officialUrl
) {
}
