package com.kmarket.navigator.backend.disclosure.domain;

import java.time.LocalDate;

public record DisclosureBackfillResult(
	LocalDate from,
	LocalDate to,
	long collectedCount,
	boolean alreadyCompleted
) {
}
