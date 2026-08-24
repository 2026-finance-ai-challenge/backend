package com.kmarket.navigator.backend.disclosure.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationCatalog;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationRepository;

@Service
public class DisclosureTitleTranslationWorker {

	private static final Logger log = LoggerFactory.getLogger(DisclosureTitleTranslationWorker.class);
	private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);

	private final DisclosureTitleTranslationRepository repository;
	private final DisclosureTitleTranslationCatalog catalog;
	private final Clock clock;
	private final String workerId;

	@Autowired
	public DisclosureTitleTranslationWorker(
		DisclosureTitleTranslationRepository repository,
		DisclosureTitleTranslationCatalog catalog
	) {
		this(repository, catalog, Clock.systemUTC(), UUID.randomUUID().toString());
	}

	DisclosureTitleTranslationWorker(
		DisclosureTitleTranslationRepository repository,
		DisclosureTitleTranslationCatalog catalog,
		Clock clock,
		String workerId
	) {
		this.repository = repository;
		this.catalog = catalog;
		this.clock = clock;
		this.workerId = workerId;
	}

	public int processBatch(int batchSize) {
		if (batchSize < 1 || batchSize > 500) {
			throw new IllegalArgumentException("Title translation batch size must be between 1 and 500");
		}
		Instant now = Instant.now(clock);
		var jobs = repository.claimJobs(
			batchSize,
			workerId,
			now,
			now.minus(PROCESSING_LEASE)
		);
		for (var job : jobs) {
			var translated = catalog.translate(job.normalizedTitle());
			if (translated.isEmpty()) {
				repository.fail(job.translationId(), "CATALOG_ENTRY_MISSING", Instant.now(clock));
				log.warn("공시 제목 번역 카탈로그 누락: sourceHash={}", job.sourceHash());
				continue;
			}
			repository.complete(
				job.translationId(),
				translated.get(),
				DisclosureTitlePolicy.MODEL_ID,
				DisclosureTitlePolicy.PROMPT_VERSION,
				Instant.now(clock)
			);
		}
		return jobs.size();
	}
}
