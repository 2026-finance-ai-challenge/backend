package com.kmarket.navigator.backend.tax.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.kmarket.navigator.backend.tax.domain.TaxDocumentReviewInput;
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
	private final TaxConversationService conversations;
	private final com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository conversationRepository;

	public TaxDocumentService(
		TaxDocumentRepository repository,
		TaxDocumentStorage storage,
		TaxFileValidator validator,
		TaxUploadRateLimiter rateLimiter,
		IdentityRepository identityRepository,
		TaxDocumentProperties properties,
		TaxDocumentGateway gateway,
		AgentSafetyIdentifier safetyIdentifier,
		TaxConversationService conversations,
		com.kmarket.navigator.backend.tax.application.port.TaxConversationRepository conversationRepository
	) {
		this.repository = repository;
		this.storage = storage;
		this.validator = validator;
		this.rateLimiter = rateLimiter;
		this.identityRepository = identityRepository;
		this.properties = properties;
		this.gateway = gateway;
		this.safetyIdentifier = safetyIdentifier;
		this.conversations = conversations;
		this.conversationRepository = conversationRepository;
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
		var room = conversations.ensureRoom(userId, "en");
		var assessment = conversationRepository.state(room.id());
		if (assessment.eligibility() == null || !assessment.verificationStarted()) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
		}
		var account = identityRepository.findActiveById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		String country = expectedResidencyCountry.toUpperCase(Locale.ROOT);
		ValidatedTaxFile validated = validator.validate(file);
		assertUploadStep(userId, documentType);
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
			assessment.eligibility() == null ? account.investorType() : assessment.eligibility().investorType(),
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
			conversationRepository.touch(userId);
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
	public TaxDocumentComparison compare(UUID userId, List<UUID> documentIds) {
		var room = conversations.ensureRoom(userId, "en");
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
			|| documents.stream().anyMatch(document -> document.status() != TaxDocumentStatus.VERIFIED
				|| document.status() == TaxDocumentStatus.FAILED
				|| document.detectedDocumentType() == null
				|| document.fields() == null
				|| document.ocrConfidence() == null
				|| document.tamperRisk() == null
				|| document.modelId() == null
				|| document.promptVersion() == null)) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		TaxDocument first = documents.getFirst();
		if (documents.stream().anyMatch(document ->
			!first.expectedResidencyCountry().equals(document.expectedResidencyCountry())
				|| first.investorType() != document.investorType())) {
			throw new BusinessException(ErrorCode.INVALID_TAX_DOCUMENT);
		}
		List<TaxDocumentReviewInput> reviewInputs = documents.stream()
			.map(document -> new TaxDocumentReviewInput(
				document.documentType(),
				document.detectedDocumentType(),
				document.status(),
				document.fields(),
				document.missingRequiredFields(),
				document.issues(),
				document.ocrConfidence(),
				document.tamperRisk(),
				document.manualReviewRequired(),
				document.modelId(),
				document.promptVersion()
			))
			.toList();
		var cached = conversationRepository.state(room.id()).comparison();
		if (cached != null) return cached;
		var result = gateway.compare(
			reviewInputs,
			first.expectedResidencyCountry(),
			first.investorType(),
			safetyIdentifier.from(userId)
		);
		conversationRepository.saveComparison(room.id(), result);
		conversationRepository.touch(userId);
		return result;
	}

	@Transactional
	public TaxDocument retry(UUID userId, UUID documentId) {
		TaxDocument document = get(userId, documentId);
		if (document.storageKey().startsWith("purged/")) throw new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_RETRYABLE);
		assertRetryStep(userId, document);
		Instant now = Instant.now(clock);
		if (!repository.retry(userId, documentId, now)) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_RETRYABLE);
		}
		repository.audit(documentId, userId, "RETRY_REQUESTED", now);
		return get(userId, documentId);
	}

	@Transactional
	public void delete(UUID userId, UUID documentId) {
		conversationRepository.lockUser(userId);
		TaxDocument document = get(userId, documentId);
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
		if (!document.storageKey().startsWith("purged/")) storage.delete(document.storageKey());
		repository.markPurged(documentId, now);
		conversationRepository.touch(userId);
	}

	private TaxDocumentFields emptyFields() {
		return new TaxDocumentFields(null, null, null, null, null, null, null, null, null);
	}

	public TaxDocumentContent original(UUID userId, UUID documentId) {
		TaxDocument document = get(userId, documentId);
		if (document.storageKey().startsWith("purged/")) throw new BusinessException(ErrorCode.TAX_DOCUMENT_NOT_FOUND);
		return new TaxDocumentContent(
			document.originalFileName(),
			document.mediaType(),
			storage.read(
				document.userId(),
				document.id(),
				document.sha256(),
				document.mediaType(),
				document.storageKey()
			)
		);
	}

	private void assertUploadStep(UUID userId, TaxDocumentType requestedType) {
		TaxDocumentType expectedType = nextExpectedType(repository.findAll(userId));
		if (expectedType != requestedType) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED, Map.of(
				"expectedDocumentType", expectedType.name()
			));
		}
	}

	private void assertRetryStep(UUID userId, TaxDocument document) {
		TaxDocumentType expectedType = nextExpectedType(repository.findAll(userId));
		if (document.documentType() != expectedType) {
			throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED, Map.of(
				"expectedDocumentType", expectedType.name()
			));
		}
	}

	private TaxDocumentType nextExpectedType(List<TaxDocument> documents) {
		for (TaxDocumentType type : requiredDocumentTypes()) {
			List<TaxDocument> supplied = documents.stream()
				.filter(document -> document.documentType() == type)
				.toList();
			if (supplied.stream().anyMatch(document -> document.status() == TaxDocumentStatus.PROCESSING)) {
				throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED, Map.of(
					"expectedDocumentType", type.name()
				));
			}
			if (supplied.stream().noneMatch(this::allowsNextStep)) {
				return type;
			}
		}
		throw new BusinessException(ErrorCode.TAX_DOCUMENT_STEP_BLOCKED);
	}

	private boolean allowsNextStep(TaxDocument document) {
		return document.status() == TaxDocumentStatus.VERIFIED;
	}

	private List<TaxDocumentType> requiredDocumentTypes() {
		return List.of(
			TaxDocumentType.RESIDENCY_CERTIFICATE,
			TaxDocumentType.APOSTILLE,
			TaxDocumentType.REDUCED_TAX_APPLICATION
		);
	}

	public record TaxDocumentContent(String originalFileName, String mediaType, byte[] content) {
	}
}
