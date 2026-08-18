package com.kmarket.navigator.backend.disclosure.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureBackfillRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureBackfillResult;

@Service
public class DisclosureBackfillHandler {

	private final DisclosureCollectionHandler collectionHandler;
	private final DisclosureBackfillRepository backfillRepository;

	public DisclosureBackfillHandler(
		DisclosureCollectionHandler collectionHandler,
		DisclosureBackfillRepository backfillRepository
	) {
		this.collectionHandler = collectionHandler;
		this.backfillRepository = backfillRepository;
	}

	public DisclosureBackfillResult run(LocalDate from, LocalDate to) {
		validateRange(from, to);
		UUID runId = UUID.randomUUID();
		DisclosureBackfillJob job = backfillRepository.startOrResume(from, to, runId);
		if (job.completed()) {
			return new DisclosureBackfillResult(from, to, job.collectedCount(), true);
		}
		if (!Objects.equals(job.runId(), runId)) {
			throw new IllegalStateException("Disclosure backfill is already running");
		}

		try {
			collectionHandler.synchronizeCorporations();
			LocalDate date = job.nextDate();
			long collectedCount = job.collectedCount();
			while (!date.isAfter(to)) {
				int saved = collectionHandler.collect(date);
				backfillRepository.advance(job.id(), runId, date, saved);
				collectedCount += saved;
				date = date.plusDays(1);
			}
			backfillRepository.complete(job.id(), runId);
			return new DisclosureBackfillResult(from, to, collectedCount, false);
		}
		catch (RuntimeException exception) {
			backfillRepository.fail(job.id(), runId, exception.getClass().getSimpleName());
			throw exception;
		}
	}

	private static void validateRange(LocalDate from, LocalDate to) {
		Objects.requireNonNull(from, "from must not be null");
		Objects.requireNonNull(to, "to must not be null");
		if (from.isAfter(to)) {
			throw new IllegalArgumentException("from must not be after to");
		}
	}
}
