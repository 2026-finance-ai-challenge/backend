package com.kmarket.navigator.backend.disclosure.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationCatalog;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationJob;
import com.kmarket.navigator.backend.translation.application.TranslationProviderException;
import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;
import com.kmarket.navigator.backend.global.error.BusinessException;

@Service
public class DisclosureTitleTranslationWorker {

	private static final Logger log = LoggerFactory.getLogger(DisclosureTitleTranslationWorker.class);
	private static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);
	private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
	private static final int AI_BATCH_SIZE = 25;

	private final DisclosureTitleTranslationRepository repository;
	private final DisclosureTitleTranslationCatalog catalog;
	private final TranslationAiGateway aiGateway;
	private final TranslationRepository translationRepository;
	private final Clock clock;
	private final String workerId;

	@Autowired
	public DisclosureTitleTranslationWorker(
		DisclosureTitleTranslationRepository repository,
		DisclosureTitleTranslationCatalog catalog,
		TranslationAiGateway aiGateway,
		TranslationRepository translationRepository
	) {
		this(repository, catalog, aiGateway, translationRepository,
			Clock.systemUTC(), UUID.randomUUID().toString());
	}

	DisclosureTitleTranslationWorker(
		DisclosureTitleTranslationRepository repository,
		DisclosureTitleTranslationCatalog catalog,
		TranslationAiGateway aiGateway,
		TranslationRepository translationRepository,
		Clock clock,
		String workerId
	) {
		this.repository = repository;
		this.catalog = catalog;
		this.aiGateway = aiGateway;
		this.translationRepository = translationRepository;
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
		List<DisclosureTitleTranslationJob> uncatalogued = new ArrayList<>();
		for (DisclosureTitleTranslationJob job : jobs) {
			var translated = catalog.translate(job.normalizedTitle());
			if (translated.isEmpty()) {
				uncatalogued.add(job);
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
		for (int start = 0; start < uncatalogued.size(); start += AI_BATCH_SIZE) {
			int end = Math.min(start + AI_BATCH_SIZE, uncatalogued.size());
			translateWithAi(uncatalogued.subList(start, end));
		}
		return jobs.size();
	}

	private void translateWithAi(List<DisclosureTitleTranslationJob> jobs) {
		List<TitleTranslationJob> requests = jobs.stream()
			.map(job -> new TitleTranslationJob(
				job.translationId(), job.sourceHash(), job.normalizedTitle(),
				DisclosureTitlePolicy.TRANSLATION_VERSION, job.attempts()
			))
			.toList();
		try {
			aiGateway.translateTitles(requests).forEach(generated -> repository.complete(
				generated.id(), generated.translatedText(), generated.modelId(),
				generated.promptVersion(), Instant.now(clock)
			));
		}
		catch (RuntimeException exception) {
			Instant failedAt = Instant.now(clock);
			for (DisclosureTitleTranslationJob job : jobs) {
				translationRepository.fail(
					job.translationId(), job.attempts(), errorCode(exception), failedAt, RETRY_DELAY
				);
			}
			log.warn("공시 제목 AI 번역 실패: count={}, error={}", jobs.size(), errorCode(exception));
		}
	}

	private static String errorCode(RuntimeException exception) {
		if (exception instanceof TranslationProviderException providerException) {
			return providerException.failure().code();
		}
		if (exception instanceof BusinessException businessException) {
			return businessException.errorCode().code();
		}
		return exception.getClass().getSimpleName();
	}
}
