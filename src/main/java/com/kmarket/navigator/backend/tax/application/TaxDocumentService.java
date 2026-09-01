package com.kmarket.navigator.backend.tax.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.kmarket.navigator.backend.chat.application.AgentSafetyIdentifier;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.identity.application.port.IdentityRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentGateway;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentStorage;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentComparison;
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
	private final TaxDocumentGateway gateway;
	private final AgentSafetyIdentifier safetyIdentifier;
	private final Clock clock = Clock.systemUTC();

	public TaxDocumentService(
		TaxDocumentRepository repository,
		TaxDocumentStorage storage,
		TaxFileValidator validator,
		TaxUploadRateLimiter rateLimiter,
		IdentityRepository identityRepository,
		TaxDocumentProperties properties,
		TaxDocumentGateway gateway,
		AgentSafetyIdentifier safetyIdentifier
	) {
		this.repository = repository;
		this.storage = storage;
		this.validator = validator;
		this.rateLimiter = rateLimiter;
		this.identityRepository = identityRepository;
		this.properties = properties;
		this.gateway = gateway;
		this.safetyIdentifier = safetyIdentifier;
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

	public TaxDocumentComparison compare(UUID userId, List<UUID> documentIds) {
		if (documentIds == null || documentIds.size() != 3
			|| documentIds.stream().anyMatch(Objects::isNull)
			|| documentIds.stream().distinct().count() != 3) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		List<TaxDocument> documents = documentIds.stream()
			.map(documentId -> get(userId, documentId))
			.toList();
		Set<TaxDocumentType> suppliedTypes = documents.stream()
			.map(TaxDocument::documentType)
			.collect(Collectors.toUnmodifiableSet());
		Set<TaxDocumentType> requiredTypes = Set.of(
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			TaxDocumentType.APOSTILLE,
			TaxDocumentType.REDUCED_TAX_APPLICATION
		);
		if (!suppliedTypes.equals(requiredTypes)
			|| documents.stream().anyMatch(document -> document.status() == TaxDocumentStatus.PROCESSING
				|| document.status() == TaxDocumentStatus.FAILED)) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		TaxDocument first = documents.getFirst();
		if (documents.stream().anyMatch(document ->
			!first.expectedResidencyCountry().equals(document.expectedResidencyCountry())
				|| first.investorType() != document.investorType())) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		List<TaxDocumentPayload> payloads = documents.stream()
			.map(document -> new TaxDocumentPayload(
				document.documentType(),
				document.originalFileName(),
				document.mediaType(),
				storage.read(
					document.userId(),
					document.id(),
					document.sha256(),
					document.mediaType(),
					document.storageKey()
				)
			))
			.toList();
		try {
			return gateway.compare(
				payloads,
				first.expectedResidencyCountry(),
				first.investorType(),
				safetyIdentifier.from(userId)
			);
		}
		finally {
			// 복호화한 원문은 내부 AI 호출이 끝나는 즉시 메모리에서 제거한다.
			payloads.forEach(TaxDocumentPayload::clear);
		}
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
