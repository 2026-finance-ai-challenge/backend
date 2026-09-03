package com.kmarket.navigator.backend.tax.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.identity.domain.InvestorType;
import com.kmarket.navigator.backend.tax.application.TaxVerificationTask;
import com.kmarket.navigator.backend.tax.application.port.TaxDocumentRepository;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentFields;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentIssue;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentStatus;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentVerification;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcTaxDocumentRepository implements TaxDocumentRepository {

	private static final String COLUMNS = """
		id, user_id, document_type, expected_residency_country, investor_type,
		original_file_name, media_type, size_bytes, sha256, storage_key, status,
		progress, stage, detected_document_type, extracted_fields,
		missing_required_fields, issues, ocr_confidence, tamper_risk,
		manual_review_required, model_id, prompt_version, request_id, attempts,
		error_code, created_at, updated_at, deleted_at
		""";
	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public JdbcTaxDocumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<TaxDocument> findDuplicate(UUID userId, TaxDocumentType type, String sha256) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM tax_document "
			+ "WHERE user_id = :userId AND document_type = :type AND sha256 = :sha256 "
			+ "AND deleted_at IS NULL AND status NOT IN ('FAILED', 'REJECTED', 'REVIEW_REQUIRED')")
			.param("userId", userId)
			.param("type", type.name())
			.param("sha256", sha256)
			.query(this::map)
			.optional();
	}

	@Override
	public TaxDocument create(TaxDocument document) {
		jdbcClient.sql("""
			INSERT INTO tax_document (
			    id, user_id, document_type, expected_residency_country, investor_type,
			    original_file_name, media_type, size_bytes, sha256, storage_key,
			    status, progress, stage, extracted_fields, missing_required_fields,
			    issues, manual_review_required, attempts, available_at, created_at, updated_at
			) VALUES (
			    :id, :userId, :documentType, :country, :investorType,
			    :fileName, :mediaType, :sizeBytes, :sha256, :storageKey,
			    :status, :progress, :stage, CAST(:fields AS jsonb), CAST(:missing AS jsonb),
			    CAST(:issues AS jsonb), :manualReviewRequired, :attempts, :availableAt,
			    :createdAt, :updatedAt
			)
			""")
			.param("id", document.id())
			.param("userId", document.userId())
			.param("documentType", document.documentType().name())
			.param("country", document.expectedResidencyCountry())
			.param("investorType", document.investorType().name())
			.param("fileName", document.originalFileName())
			.param("mediaType", document.mediaType())
			.param("sizeBytes", document.sizeBytes())
			.param("sha256", document.sha256())
			.param("storageKey", document.storageKey())
			.param("status", document.status().name())
			.param("progress", document.progress())
			.param("stage", document.stage())
			.param("fields", json(document.fields()))
			.param("missing", json(document.missingRequiredFields()))
			.param("issues", json(document.issues()))
			.param("manualReviewRequired", document.manualReviewRequired())
			.param("attempts", document.attempts())
			.param("availableAt", timestamp(document.createdAt()))
			.param("createdAt", timestamp(document.createdAt()))
			.param("updatedAt", timestamp(document.updatedAt()))
			.update();
		return document;
	}

	@Override
	public List<TaxDocument> findAll(UUID userId) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM tax_document "
			+ "WHERE user_id = :userId AND deleted_at IS NULL ORDER BY created_at DESC, id DESC")
			.param("userId", userId)
			.query(this::map)
			.list();
	}

	@Override
	public Optional<TaxDocument> findOwned(UUID userId, UUID documentId) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM tax_document "
			+ "WHERE id = :documentId AND user_id = :userId AND deleted_at IS NULL")
			.param("documentId", documentId)
			.param("userId", userId)
			.query(this::map)
			.optional();
	}

	@Override
	public List<TaxDocument> findAllIncludingDeleted(UUID userId) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM tax_document WHERE user_id = :id")
			.param("id", userId).query(this::map).list();
	}
	@Override
	public void deleteAll(UUID userId) {
		jdbcClient.sql("DELETE FROM tax_document WHERE user_id = :id").param("id", userId).update();
	}
	@Override
	public void purgeFailedContent(UUID documentId, Instant now) {
		jdbcClient.sql("""
			UPDATE tax_document SET storage_key = 'purged/' || id, extracted_fields = '{}'::jsonb,
			    original_file_name = 'removed', purged_at = :now
			WHERE id = :id AND status IN ('FAILED', 'REJECTED', 'REVIEW_REQUIRED')
			""").param("id", documentId).param("now", timestamp(now)).update();
	}

	@Override
	@Transactional
	public List<TaxVerificationTask> claim(
		String workerId,
		int limit,
		Instant now,
		Instant staleBefore
	) {
		return jdbcClient.sql("""
			WITH candidates AS (
			    SELECT id
			    FROM tax_document
			    WHERE status = 'PROCESSING'
			      AND deleted_at IS NULL
			      AND available_at <= :now
			      AND (locked_at IS NULL OR locked_at < :staleBefore)
			    ORDER BY available_at, created_at
			    FOR UPDATE SKIP LOCKED
			    LIMIT :limit
			)
			UPDATE tax_document document
			SET locked_at = :now,
			    locked_by = :workerId,
			    attempts = attempts + 1,
			    progress = 45,
			    stage = 'OCR_RUNNING',
			    updated_at = :now
			FROM candidates
			WHERE document.id = candidates.id
			RETURNING document.*
			""")
			.param("now", timestamp(now))
			.param("staleBefore", timestamp(staleBefore))
			.param("workerId", workerId)
			.param("limit", limit)
			.query(this::map)
			.list().stream()
			.map(TaxVerificationTask::new)
			.toList();
	}

	@Override
	public void complete(
		UUID documentId,
		TaxDocumentVerification verification,
		String requestId,
		Instant now
	) {
		jdbcClient.sql("""
			UPDATE tax_document
			SET status = :status,
			    progress = 100,
			    stage = 'VERIFICATION_COMPLETE',
			    detected_document_type = :detectedType,
			    extracted_fields = CAST(:fields AS jsonb),
			    missing_required_fields = CAST(:missing AS jsonb),
			    issues = CAST(:issues AS jsonb),
			    ocr_confidence = :ocrConfidence,
			    tamper_risk = :tamperRisk,
			    manual_review_required = :manualReviewRequired,
			    model_id = :modelId,
			    prompt_version = :promptVersion,
			    request_id = :requestId,
			    locked_at = NULL,
			    locked_by = NULL,
			    error_code = NULL,
			    updated_at = :now
			WHERE id = :documentId AND status = 'PROCESSING' AND deleted_at IS NULL
			""")
			.param("status", verification.status().name())
			.param("detectedType", verification.detectedDocumentType().name())
			.param("fields", json(verification.fields()))
			.param("missing", json(verification.missingRequiredFields()))
			.param("issues", json(verification.issues()))
			.param("ocrConfidence", verification.ocrConfidence())
			.param("tamperRisk", verification.tamperRisk())
			.param("manualReviewRequired", verification.manualReviewRequired())
			.param("modelId", verification.modelId())
			.param("promptVersion", verification.promptVersion())
			.param("requestId", requestId)
			.param("now", timestamp(now))
			.param("documentId", documentId)
			.update();
	}

	@Override
	public void fail(
		UUID documentId,
		String errorCode,
		boolean terminal,
		Instant availableAt,
		Instant now
	) {
		jdbcClient.sql("""
			UPDATE tax_document
			SET status = CASE WHEN :terminal THEN 'FAILED' ELSE 'PROCESSING' END,
			    progress = CASE WHEN :terminal THEN 100 ELSE 45 END,
			    stage = CASE WHEN :terminal THEN 'VERIFICATION_FAILED' ELSE 'RETRY_WAIT' END,
			    error_code = :errorCode,
			    available_at = :availableAt,
			    locked_at = NULL,
			    locked_by = NULL,
			    updated_at = :now
			WHERE id = :documentId AND status = 'PROCESSING' AND deleted_at IS NULL
			""")
			.param("terminal", terminal)
			.param("errorCode", errorCode)
			.param("availableAt", timestamp(availableAt))
			.param("now", timestamp(now))
			.param("documentId", documentId)
			.update();
	}

	@Override
	public boolean retry(UUID userId, UUID documentId, Instant now) {
		return jdbcClient.sql("""
			UPDATE tax_document
			SET status = 'PROCESSING', progress = 10, stage = 'QUEUED', attempts = 0,
			    available_at = :now, locked_at = NULL, locked_by = NULL,
			    error_code = NULL, updated_at = :now
			WHERE id = :documentId AND user_id = :userId AND status = 'FAILED'
			  AND deleted_at IS NULL
			""")
			.param("now", timestamp(now))
			.param("documentId", documentId)
			.param("userId", userId)
			.update() == 1;
	}

	@Override
	public boolean softDelete(
		UUID userId,
		UUID documentId,
		Instant deletedAt,
		Instant purgeAfter
	) {
		return jdbcClient.sql("""
			UPDATE tax_document
			SET deleted_at = :deletedAt, purge_after = :purgeAfter,
			    locked_at = NULL, locked_by = NULL, updated_at = :deletedAt
			WHERE id = :documentId AND user_id = :userId AND deleted_at IS NULL
			""")
			.param("deletedAt", timestamp(deletedAt))
			.param("purgeAfter", timestamp(purgeAfter))
			.param("documentId", documentId)
			.param("userId", userId)
			.update() == 1;
	}

	@Override
	public List<TaxDocument> findPurgeCandidates(Instant now, int limit) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM tax_document "
			+ "WHERE purged_at IS NULL AND ((deleted_at IS NOT NULL AND purge_after <= :now) "
			+ "OR status IN ('FAILED', 'REJECTED', 'REVIEW_REQUIRED')) "
			+ "ORDER BY purge_after, id LIMIT :limit")
			.param("now", timestamp(now))
			.param("limit", limit)
			.query(this::map)
			.list();
	}

	@Override
	public void markPurged(UUID documentId, Instant now) {
		jdbcClient.sql("""
			UPDATE tax_document
			SET original_file_name = 'deleted', storage_key = 'purged/' || id,
			    extracted_fields = '{}'::jsonb, missing_required_fields = '[]'::jsonb,
			    issues = '[]'::jsonb, model_id = NULL, prompt_version = NULL,
			    request_id = NULL, error_code = NULL, purged_at = :now, updated_at = :now
			WHERE id = :documentId AND deleted_at IS NOT NULL AND purged_at IS NULL
			""")
			.param("documentId", documentId)
			.param("now", timestamp(now))
			.update();
	}

	@Override
	public void audit(UUID documentId, UUID userId, String action, Instant occurredAt) {
		jdbcClient.sql("""
			INSERT INTO tax_document_audit (document_id, user_id, action, occurred_at)
			VALUES (:documentId, :userId, :action, :occurredAt)
			""")
			.param("documentId", documentId)
			.param("userId", userId)
			.param("action", action)
			.param("occurredAt", timestamp(occurredAt))
			.update();
	}

	private TaxDocument map(ResultSet resultSet, int rowNumber) throws SQLException {
		String detected = resultSet.getString("detected_document_type");
		return new TaxDocument(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("user_id", UUID.class),
			TaxDocumentType.valueOf(resultSet.getString("document_type")),
			resultSet.getString("expected_residency_country"),
			InvestorType.valueOf(resultSet.getString("investor_type")),
			resultSet.getString("original_file_name"),
			resultSet.getString("media_type"),
			resultSet.getLong("size_bytes"),
			resultSet.getString("sha256"),
			resultSet.getString("storage_key"),
			TaxDocumentStatus.valueOf(resultSet.getString("status")),
			resultSet.getInt("progress"),
			resultSet.getString("stage"),
			detected == null ? null : TaxDocumentType.valueOf(detected),
			read(resultSet.getString("extracted_fields"), TaxDocumentFields.class),
			read(resultSet.getString("missing_required_fields"), new TypeReference<List<String>>() { }),
			read(resultSet.getString("issues"), new TypeReference<List<TaxDocumentIssue>>() { }),
			decimal(resultSet, "ocr_confidence"),
			decimal(resultSet, "tamper_risk"),
			resultSet.getBoolean("manual_review_required"),
			resultSet.getString("model_id"),
			resultSet.getString("prompt_version"),
			resultSet.getString("request_id"),
			resultSet.getInt("attempts"),
			resultSet.getString("error_code"),
			resultSet.getTimestamp("created_at").toInstant(),
			resultSet.getTimestamp("updated_at").toInstant(),
			resultSet.getTimestamp("deleted_at") == null
				? null
				: resultSet.getTimestamp("deleted_at").toInstant()
		);
	}

	private BigDecimal decimal(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getBigDecimal(column);
	}

	private Timestamp timestamp(Instant value) {
		return Timestamp.from(value);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("Tax document JSON serialization failed", exception);
		}
	}

	private <T> T read(String value, Class<T> type) {
		try {
			return objectMapper.readValue(value, type);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("Tax document JSON parsing failed", exception);
		}
	}

	private <T> T read(String value, TypeReference<T> type) {
		try {
			return objectMapper.readValue(value, type);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException("Tax document JSON parsing failed", exception);
		}
	}
}
