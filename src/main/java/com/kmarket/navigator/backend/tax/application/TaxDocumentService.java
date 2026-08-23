package com.kmarket.navigator.backend.tax.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.application.port.IdentityRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentStorage;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.infrastructure.storage.TaxDocumentProperties;

@Service
public class TaxDocumentService {

	private final TaxDocumentRepository repository;
	private final TaxDocumentStorage storage;
	private final TaxFileValidator validator;
	private final TaxUploadRateLimiter rateLimiter;
	private final IdentityRepository identityRepository;
	private final TaxDocumentProperties properties;
	private final Clock clock = Clock.systemUTC();

	public TaxDocumentService(
		TaxDocumentRepository repository,
		TaxDocumentStorage storage,
		TaxFileValidator validator,
		TaxUploadRateLimiter rateLimiter,
		IdentityRepository identityRepository,
		TaxDocumentProperties properties
	) {
		this.repository = repository;
		this.storage = storage;
		this.validator = validator;
		this.rateLimiter = rateLimiter;
		this.identityRepository = identityRepository;
		this.properties = properties;
	}

	@Transactional
	public TaxDocument upload(
		UUID userId,
		TaxDocumentType documentType,
		String expectedResidencyCountry,
		MultipartFile file
	) {
		if (documentType == TaxDocumentType.UNKNOWN) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		rateLimiter.check(userId);
		var account = identityRepository.findActiveById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		String country = expectedResidencyCountry.toUpperCase(Locale.ROOT);
		ValidatedTaxFile validated = validator.validate(file);
		var duplicate = repository.findDuplicate(userId, documentType, validated.sha256());
		if (duplicate.isPresent()) {
			return duplicate.get();
		}
		UUID documentId = UUID.randomUUID();
		Instant now = Instant.now(clock);
		String storageKey = storage.store(
			userId,
			documentId,
			validated.sha256(),
			validated.mediaType(),
			validated.content()
		);
		TaxDocument document = new TaxDocument(
			documentId,
			userId,
			documentType,
			country,
			account.investorType(),
			validated.originalFileName(),
			validated.mediaType(),
			validated.content().length,
			validated.sha256(),
			storageKey,
			TaxDocumentStatus.PROCESSING,
			10,
			"QUEUED",
			null,
			emptyFields(),
			List.of(),
			List.of(),
			null,
			null,
			true,
			null,
			null,
			null,
			0,
			null,
			now,
			now,
			null
		);
		try {
			repository.create(document);
			repository.audit(documentId, userId, "UPLOADED", now);
			return document;
		}
		catch (RuntimeException exception) {
			storage.delete(storageKey);
			throw exception;
		}
	}

	public List<TaxDocument> list(UUID userId) {
		return repository.findAll(userId);
	}

	public TaxDocument get(UUID userId, UUID documentId) {
		return repository.findOwned(userId, documentId)
			.orElseThrow(() -> new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_FOUND));
	}

	@Transactional
	public TaxDocument retry(UUID userId, UUID documentId) {
		get(userId, documentId);
		Instant now = Instant.now(clock);
		if (!repository.retry(userId, documentId, now)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_RETRYABLE);
		}
		repository.audit(documentId, userId, "RETRY_REQUESTED", now);
		return get(userId, documentId);
	}

	@Transactional
	public void delete(UUID userId, UUID documentId) {
		get(userId, documentId);
		Instant now = Instant.now(clock);
		if (!repository.softDelete(
			userId,
			documentId,
			now,
			now.plus(properties.retentionAfterDelete())
		)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_FOUND);
		}
		repository.audit(documentId, userId, "DELETION_SCHEDULED", now);
	}

	private TaxDocumentFields emptyFields() {
		return new TaxDocumentFields(null, null, null, null, null, null, null, null, null);
	}
}
