package com.kmarket.navigator.backend.translation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.TranslationJob;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.concurrent.BoundedTasks;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TranslationWorker {

	private static final Logger log = LoggerFactory.getLogger(TranslationWorker.class);
	private static final int BATCH_SIZE = 4;
	private static final int TITLE_BATCH_SIZE = 5;
	private static final Duration TRANSIENT_FAILURE_COOLDOWN = Duration.ofSeconds(15);
	private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofMinutes(1);
	private final TranslationRepository repository;
	private final TranslationAiGateway aiGateway;
	private final TranslationGenerationGuard guard;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final Duration providerFailureCooldown;
	private final String workerId = UUID.randomUUID().toString();
	private volatile Instant nextProviderAttemptAt = Instant.EPOCH;

	@Autowired
	public TranslationWorker(
		TranslationRepository repository,
		TranslationAiGateway aiGateway,
		TranslationGenerationGuard guard,
		ObjectMapper objectMapper,
		@Value("${kmarket.translation.provider-failure-cooldown:15m}") Duration providerFailureCooldown
	) {
		this(repository, aiGateway, guard, objectMapper, Clock.systemUTC(), providerFailureCooldown);
	}

	TranslationWorker(
		TranslationRepository repository,
		TranslationAiGateway aiGateway,
		TranslationGenerationGuard guard,
		ObjectMapper objectMapper,
		Clock clock,
		Duration providerFailureCooldown
	) {
		this.repository = repository;
		this.aiGateway = aiGateway;
		this.guard = guard;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.providerFailureCooldown = providerFailureCooldown;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.translation.generation-interval:2s}",
		initialDelayString = "${kmarket.translation.generation-initial-delay:30s}"
	)
	@SchedulerLock(name = "on-demand-translation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0.5S")
	public void process() {
		Instant now = Instant.now(clock);
		if (now.isBefore(nextProviderAttemptAt)) {
			return;
		}
		List<TranslationJob> jobs = repository.claim(
			BATCH_SIZE, workerId, now, now.minus(Duration.ofMinutes(5))
		);
		BoundedTasks.forEach(jobs, BATCH_SIZE, this::process);
	}

	@Scheduled(
		fixedDelayString = "${kmarket.translation.title-generation-interval:2s}",
		initialDelayString = "${kmarket.translation.title-generation-initial-delay:10s}"
	)
	@SchedulerLock(name = "title-translation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT0.5S")
	public void processTitleBatch() {
		processTitles(Instant.now(clock));
	}

	private void processTitles(Instant now) {
		if (now.isBefore(nextProviderAttemptAt)) {
			return;
		}
		List<TitleTranslationJob> jobs = repository.claimNewsTitles(
			TITLE_BATCH_SIZE, workerId, now, now.minus(Duration.ofMinutes(5))
		);
		if (jobs.isEmpty()) {
			return;
		}
		processTitleSubset(jobs, now);
	}

	private void processTitleSubset(List<TitleTranslationJob> jobs, Instant now) {
		try {
			var generated = aiGateway.translateTitles(jobs);
			generated.forEach(title -> repository.completeNewsTitle(title, Instant.now(clock)));
		}
		catch (RuntimeException exception) {
			Duration cooldown = cooldown(exception);
			deferProvider(now.plus(cooldown));
			String errorCode = errorCode(exception);
			for (TitleTranslationJob job : jobs) {
				repository.fail(job.id(), job.attempts(), errorCode,
					Instant.now(clock), cooldown);
			}
			log.warn("News title translation batch failed size={} error={} cooldownSeconds={}",
				jobs.size(), errorCode, cooldown.toSeconds());
		}
	}

	private Duration cooldown(RuntimeException exception) {
		if (exception instanceof TranslationProviderException providerException) {
			return switch (providerException.failure()) {
				case INVALID_OUTPUT, INCOMPLETE -> Duration.ZERO;
				case QUOTA_EXHAUSTED -> providerFailureCooldown;
				case RATE_LIMITED -> RATE_LIMIT_COOLDOWN;
				case TIMEOUT, UNAVAILABLE -> TRANSIENT_FAILURE_COOLDOWN;
			};
		}
		return providerFailureCooldown;
	}

	private void process(TranslationJob job) {
		TranslationGenerationGuard.Guard acquired = guard.tryAcquire(
			job.sourceHash() + ":" + job.targetLocale()
		);
		if (acquired == null) {
			repository.fail(job.id(), job.attempts(), "DUPLICATE_GENERATION_LOCKED",
				Instant.now(clock), Duration.ofSeconds(5));
			return;
		}
		try (acquired) {
			GeneratedTranslation generated = switch (job.kind()) {
				case NEWS_NARRATIVE -> generateNews(job);
				case DISCLOSURE_SECTION -> generateSection(job);
			};
			repository.complete(job.id(), generated, Instant.now(clock));
		}
		catch (RuntimeException exception) {
			Duration delay = cooldown(exception);
			deferProvider(Instant.now(clock).plus(delay));
			String code = errorCode(exception);
			repository.fail(job.id(), job.attempts(), code, Instant.now(clock), delay);
			log.warn("Translation generation failed id={} kind={} error={}",
				job.id(), job.kind(), code);
		}
	}

	private synchronized void deferProvider(Instant until) {
		if (until.isAfter(nextProviderAttemptAt)) nextProviderAttemptAt = until;
	}

	private GeneratedTranslation generateNews(TranslationJob job) {
		JsonNode source = objectMapper.readTree(job.canonicalSource());
		List<String> paragraphs = new ArrayList<>();
		source.path("paragraphs").forEach(value -> paragraphs.add(value.asString()));
		if ("news-bilingual-v1".equals(job.translationVersion())) {
			return aiGateway.streamNews(job.sourceHash(), source.path("title").asString(), paragraphs,
				source.path("content_availability").asString(), job.translationVersion(),
				partial -> repository.progress(job.id(), partial, Instant.now(clock)));
		}
		return aiGateway.translateNews(
			job.sourceHash(), source.path("title").asString(), paragraphs,
			source.path("content_availability").asString(), job.targetLocale(),
			job.translationVersion()
		);
	}

	private GeneratedTranslation generateSection(TranslationJob job) {
		JsonNode source = objectMapper.readTree(job.canonicalSource());
		JsonNode context = job.context();
		return aiGateway.translateDisclosureSection(
			context.path("receipt_number").asString(), context.path("document_version").asInt(),
			context.path("section_id").asString(), job.sourceHash(),
			nullableText(source.get("heading")), nullableText(source.get("text")),
			nullableText(source.get("table_data_json")), job.translationVersion()
		);
	}

	private static String nullableText(JsonNode value) {
		return value == null || value.isNull() ? null : value.asString();
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
