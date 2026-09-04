package com.kmarket.navigator.backend.disclosure.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FilingEvidence(String receiptNumber, String stockCode, String title, LocalDate filedDate,
	Instant detectedAt, String content, List<UUID> sectionIds, String retrievalMethod) { }
