package com.kmarket.navigator.backend.disclosure.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DisclosureDetail(
	String receiptNumber,
	String corpCode,
	String issuerNameKo,
	String issuerNameEn,
	String stockCode,
	Market market,
	DisclosureType type,
	String titleKo,
	String titleEn,
	String submitter,
	LocalDate filedDate,
	Instant detectedAt,
	String remark,
	boolean correction,
	DocumentStatus documentStatus,
	IndexStatus indexStatus,
	String officialUrl,
	List<DisclosureDocument> documents,
	List<DisclosureVersion> versions
) {
	public DisclosureDetail {
		documents = List.copyOf(documents);
		versions = List.copyOf(versions);
	}
}
