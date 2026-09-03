package com.kmarket.navigator.backend.tax.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.chat.application.AgentSafetyIdentifier;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentGateway;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentStorage;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class TaxDocumentWorker {

	private static final Logger log = LoggerFactory.getLogger(TaxDocumentWorker.class);
	private static final int MAX_ATTEMPTS = 3;
	private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);
	private final TaxDocumentRepository repository;
	private final TaxDocumentStorage storage;
	private final TaxDocumentGateway gateway;
	private final TaxDocumentVerificationPolicy policy;
	private final AgentSafetyIdentifier safetyIdentifier;
	private final Clock clock = Clock.systemUTC();
	private final String workerId = "tax-" + UUID.randomUUID();

	public TaxDocumentWorker(
		TaxDocumentRepository repository,
		TaxDocumentStorage storage,
		TaxDocumentGateway gateway,
		TaxDocumentVerificationPolicy policy,
		AgentSafetyIdentifier safetyIdentifier
	) {
		this.repository = repository;
		this.storage = storage;
		this.gateway = gateway;
		this.policy = policy;
		this.safetyIdentifier = safetyIdentifier;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.tax.documents.verification-interval:2s}",
		initialDelayString = "${kmarket.tax.documents.verification-initial-delay:30s}"
	)
	@SchedulerLock(name = "tax-document-verification", lockAtMostFor = "PT4M")
	public void process() {
		Instant now = Instant.now(clock);
		for (TaxVerificationTask task : repository.claim(
			workerId,
			3,
			now,
			now.minus(PROCESSING_TIMEOUT)
		)) {
			verify(task.document());
		}
	}

	@Scheduled(cron = "${kmarket.tax.documents.purge-cron:0 40 4 * * *}")
	@SchedulerLock(name = "tax-document-purge", lockAtMostFor = "PT20M")
	public void purgeDeleted() {
		Instant now = Instant.now(clock);
		for (TaxDocument document : repository.findPurgeCandidates(now, 100)) {
			storage.delete(document.storageKey());
			repository.audit(document.id(), document.userId(), "CONTENT_PURGED", now);
			repository.markPurged(document.id(), now);
		}
	}

	private void verify(TaxDocument document) {
		try {
			byte[] content = storage.read(
				document.userId(),
				document.id(),
				document.sha256(),
				document.mediaType(),
				document.storageKey()
			);
			var generated = gateway.verify(
				document.documentType(),
				document.originalFileName(),
				document.mediaType(),
				content,
				document.expectedResidencyCountry(),
				document.investorType(),
				safetyIdentifier.from(document.userId())
			);
			var verified = policy.validate(document, generated);
			Instant now = Instant.now(clock);
			repository.complete(document.id(), verified, "tax-" + UUID.randomUUID(), now);
			repository.audit(document.id(), document.userId(), "VERIFICATION_COMPLETED", now);
		}
		catch (BusinessException exception) {
			fail(document, exception.errorCode().code());
		}
		catch (RuntimeException exception) {
			fail(document, exception.getClass().getSimpleName());
		}
	}

	private void fail(TaxDocument document, String errorCode) {
		boolean terminal = document.attempts() >= MAX_ATTEMPTS;
		long delay = Math.min(60, 5L << Math.max(0, document.attempts() - 1));
		Instant now = Instant.now(clock);
		repository.fail(
			document.id(),
			errorCode,
			terminal,
			now.plusSeconds(delay),
			now
		);
		log.warn(
			"Tax document verification failed documentId={} attempt={} terminal={}",
			document.id(),
			document.attempts(),
			terminal
		);
	}
}
