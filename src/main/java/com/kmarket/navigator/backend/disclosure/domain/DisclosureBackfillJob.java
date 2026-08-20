package com.kmarket.navigator.backend.disclosure.domain;

import java.time.LocalDate;
import java.util.UUID;

public record DisclosureBackfillJob(
	UUID id,
	LocalDate from,
	LocalDate to,
	LocalDate nextDate,
	DisclosureBackfillStatus status,
	UUID runId,
	long collectedCount
) {

	public boolean completed() {
		return status == DisclosureBackfillStatus.COMPLETED;
	}
}
