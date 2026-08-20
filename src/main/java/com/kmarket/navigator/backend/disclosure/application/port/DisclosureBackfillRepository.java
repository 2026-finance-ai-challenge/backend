package com.kmarket.navigator.backend.disclosure.application.port;

import java.time.LocalDate;
import java.util.UUID;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillJob;

public interface DisclosureBackfillRepository {

	DisclosureBackfillJob startOrResume(LocalDate from, LocalDate to, UUID runId);

	void advance(
		UUID jobId,
		UUID runId,
		LocalDate expectedNextDate,
		LocalDate processedThroughDate,
		int collectedCount
	);

	void complete(UUID jobId, UUID runId);

	void fail(UUID jobId, UUID runId, String errorCode);
}
