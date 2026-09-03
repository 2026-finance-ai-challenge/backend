package com.kmarket.navigator.backend.tax.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.tax.application.TaxVerificationTask;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

public interface TaxDocumentRepository {

	Optional<TaxDocument> findDuplicate(UUID userId, TaxDocumentType type, String sha256);

	TaxDocument create(TaxDocument document);

	List<TaxDocument> findAll(UUID userId);
	List<TaxDocument> findAllIncludingDeleted(UUID userId);
	void deleteAll(UUID userId);
	void purgeFailedContent(UUID documentId, Instant now);

	Optional<TaxDocument> findOwned(UUID userId, UUID documentId);

	List<TaxVerificationTask> claim(String workerId, int limit, Instant now, Instant staleBefore);

	void complete(UUID documentId, TaxDocumentVerification verification, String requestId, Instant now);

	void fail(UUID documentId, String errorCode, boolean terminal, Instant availableAt, Instant now);

	boolean retry(UUID userId, UUID documentId, Instant now);

	boolean softDelete(UUID userId, UUID documentId, Instant deletedAt, Instant purgeAfter);

	List<TaxDocument> findPurgeCandidates(Instant now, int limit);

	void markPurged(UUID documentId, Instant now);

	void audit(UUID documentId, UUID userId, String action, Instant occurredAt);
}
