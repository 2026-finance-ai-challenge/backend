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
	private final com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository conversations;
	private final Clock clock = Clock.systemUTC();
	private final String workerId = "tax-" + UUID.randomUUID();

	public TaxDocumentWorker(
		TaxDocumentRepository repository,
		TaxDocumentStorage storage,
		TaxDocumentGateway gateway,
		TaxDocumentVerificationPolicy policy,
		AgentSafetyIdentifier safetyIdentifier,
		com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository conversations
	) {
		this.repository = repository;
		this.storage = storage;
		this.gateway = gateway;
		this.policy = policy;
		this.safetyIdentifier = safetyIdentifier;
		this.conversations = conversations;
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
			1,
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
			repository.purgeFailedContent(document.id(), now);
		}
	}

	@Scheduled(fixedDelayString = "${kmarket.tax.documents.preview-backfill-interval:30s}", initialDelayString = "${kmarket.tax.documents.preview-backfill-initial-delay:60s}")
	@SchedulerLock(name = "tax-document-preview-backfill", lockAtMostFor = "PT10M")
	public void backfillPreviewFields() {
		repository.findPreviewBackfillCandidate(Instant.now(clock)).ifPresent(document -> {
			byte[] content = null;
			try {
				content = storage.read(document.userId(), document.id(), document.sha256(), document.mediaType(), document.storageKey());
				var generated = gateway.verify(document.documentType(), document.originalFileName(), document.mediaType(), content,
					document.expectedResidencyCountry(), document.investorType(), safetyIdentifier.from(document.userId()));
				var old = document.fields();
				var fields = generated.fields();
				// 기존 판정과 신원 정보는 바꾸지 않고 동일 원본의 누락된 보조 정보만 복구한다.
				if (generated.status() != com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus.VERIFIED
					|| !Integer.valueOf(2).equals(fields.previewVersion())
					|| !normalized(old.holderName()).equals(normalized(fields.holderName()))
					|| !normalized(old.documentNumber()).equals(normalized(fields.documentNumber()))
					|| !java.util.Objects.equals(old.treatyCountry(), fields.treatyCountry())) throw new IllegalStateException("Preview fields require review");
				repository.updatePreviewFields(document.id(), new com.kmarket.navigator.backend.tax.domain.TaxDocumentFields(
					old.holderName(), old.residencyCountry(), old.issueDate(), old.expiryDate(), old.issuingAuthority(), old.documentNumber(),
					old.apostilleCountry(), old.treatyCountry(), old.investorType(), fields.birthDate(), fields.phoneNumber(), fields.address(), 2), Instant.now(clock));
				repository.audit(document.id(), document.userId(), "PREVIEW_FIELDS_RESTORED", Instant.now(clock));
			} catch (RuntimeException exception) {
				repository.failPreviewBackfill(document.id(), Instant.now(clock).plusSeconds(300));
				log.warn("Tax preview field recovery failed documentId={}", document.id());
			} finally { if (content != null) java.util.Arrays.fill(content, (byte) 0); }
		});
	}

	private static String normalized(String value) {
		return value == null ? "" : value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
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
			if (repository.findOwned(document.userId(), document.id()).isPresent()) {
				repository.audit(document.id(), document.userId(), "VERIFICATION_COMPLETED", now);
				conversations.touch(document.userId());
				if (verified.status() != com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus.VERIFIED) removeFailedContent(document, now);
			}
		}
		catch (BusinessException exception) {
			fail(document, exception.errorCode().code());
		}
		catch (RuntimeException exception) {
			fail(document, exception.getClass().getSimpleName());
		}
	}

	private void fail(TaxDocument document, String errorCode) {
		boolean terminal = document.attempts() >= MAX_ATTEMPTS
			|| errorCode.equals(com.kmarket.navigator.backend.global.error.ErrorCode.INVALID_TAX_DOCUMENT.code());
		long delay = Math.min(60, 5L << Math.max(0, document.attempts() - 1));
		Instant now = Instant.now(clock);
		repository.fail(
			document.id(),
			errorCode,
			terminal,
			now.plusSeconds(delay),
			now
		);
		if (terminal) {
			removeFailedContent(document, now);
			conversations.touch(document.userId());
		}
		log.warn(
			"Tax document verification failed documentId={} attempt={} terminal={}",
			document.id(),
			document.attempts(),
			terminal
		);
	}

	private void removeFailedContent(TaxDocument document, Instant now) {
		storage.delete(document.storageKey());
		repository.purgeFailedContent(document.id(), now);
	}
}
