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

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class TranslationWorker {

	private static final Logger log = LoggerFactory.getLogger(TranslationWorker.class);
	private static final int BATCH_SIZE = 10;
	private static final int TITLE_BATCH_SIZE = 10;
	private final TranslationRepository repository;
	private final TranslationAiGateway aiGateway;
	private final TranslationGenerationGuard guard;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final Duration providerFailureCooldown;
	private final String workerId = UUID.randomUUID().toString();
	private volatile Instant nextTitleAttemptAt = Instant.EPOCH;

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
		processTitles(now);
		List<TranslationJob> jobs = repository.claim(
			BATCH_SIZE, workerId, now, now.minus(Duration.ofMinutes(5))
		);
		for (TranslationJob job : jobs) {
			process(job);
		}
	}

	private void processTitles(Instant now) {
		if (now.isBefore(nextTitleAttemptAt)) {
			return;
		}
		List<TitleTranslationJob> jobs = repository.claimNewsTitles(
			TITLE_BATCH_SIZE, workerId, now, now.minus(Duration.ofMinutes(5))
		);
		if (jobs.isEmpty()) {
			return;
		}
		try {
			var generated = aiGateway.translateTitles(jobs);
			generated.forEach(title -> repository.completeNewsTitle(title, Instant.now(clock)));
			nextTitleAttemptAt = Instant.EPOCH;
		}
		catch (RuntimeException exception) {
			nextTitleAttemptAt = now.plus(providerFailureCooldown);
			String errorCode = errorCode(exception);
			for (TitleTranslationJob job : jobs) {
				Duration backoff = Duration.ofSeconds(Math.min(3_600, 15L << Math.min(job.attempts(), 7)));
				Duration delay = backoff.compareTo(providerFailureCooldown) >= 0
					? backoff : providerFailureCooldown;
				repository.fail(job.id(), job.attempts(), errorCode,
					Instant.now(clock), delay);
			}
			log.warn("News title translation batch failed size={} error={} cooldownSeconds={}",
				jobs.size(), errorCode, providerFailureCooldown.toSeconds());
		}
	}

	private void process(TranslationJob job) {
		TranslationGenerationGuard.Guard acquired = guard.tryAcquire(job.sourceHash());
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
			Duration delay = Duration.ofSeconds(Math.min(3_600, 15L << Math.min(job.attempts(), 7)));
			repository.fail(job.id(), job.attempts(), exception.getClass().getSimpleName(),
				Instant.now(clock), delay);
			log.warn("Translation generation failed id={} kind={} type={}",
				job.id(), job.kind(), exception.getClass().getSimpleName());
		}
	}

	private GeneratedTranslation generateNews(TranslationJob job) {
		JsonNode source = objectMapper.readTree(job.canonicalSource());
		List<String> paragraphs = new ArrayList<>();
		source.path("paragraphs").forEach(value -> paragraphs.add(value.asString()));
		return aiGateway.translateNews(
			job.sourceHash(), source.path("title").asString(), paragraphs,
			source.path("content_availability").asString(), job.translationVersion()
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
		if (exception instanceof BusinessException businessException) {
			return businessException.errorCode().code();
		}
		return exception.getClass().getSimpleName();
	}
}
