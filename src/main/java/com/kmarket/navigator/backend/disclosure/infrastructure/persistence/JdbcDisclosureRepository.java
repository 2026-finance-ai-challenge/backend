package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.application.DisclosureTitlePolicy;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartCorporation;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsight;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSignalJob;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.global.text.EnglishTextPolicy;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureVersion;
import com.kmarket.navigator.backend.disclosure.domain.DocumentStatus;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;
import com.kmarket.navigator.backend.news.domain.NewsAnalysis;

@Repository
class JdbcDisclosureRepository implements DisclosureRepository {

	private static final String DOCUMENT_JOB = "DISCLOSURE_DOCUMENT";
	private static final String EMBEDDING_JOB = "DISCLOSURE_EMBEDDING";
	private static final String METADATA_EMBEDDING_JOB = "DISCLOSURE_METADATA_EMBEDDING";
	private static final String SIGNAL_JOB = "DISCLOSURE_SIGNAL";
	private static final String DOCUMENT_PARSER_VERSION = "opendart-html-v5";
	private static final String OPEN_DART_DOCUMENT_PROVIDER = "OPEN_DART_DOCUMENT";

	private final JdbcClient jdbcClient;
	private final DisclosurePayloadCodec payloadCodec;

	JdbcDisclosureRepository(JdbcClient jdbcClient, DisclosurePayloadCodec payloadCodec) {
		this.jdbcClient = jdbcClient;
		this.payloadCodec = payloadCodec;
	}

	@Override
	@Transactional
	public void upsertCorporations(List<OpenDartCorporation> corporations) {
		for (OpenDartCorporation corporation : corporations) {
			UUID issuerId = upsertIssuer(
				corporation.corpCode(),
				corporation.nameKo(),
				corporation.nameEn(),
				null
			);
			upsertSecurity(issuerId, corporation.stockCode(), Market.UNKNOWN);
		}
	}

	@Override
	@Transactional
	public void replaceCommonStockUniverse(List<ListedCommonStock> stocks) {
		if (stocks.isEmpty()) {
			throw new IllegalArgumentException("Common stock universe must not be empty");
		}
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcClient.sql("""
			UPDATE security
			SET common_stock = FALSE, active = FALSE, master_updated_at = :now
			""")
			.param("now", now)
			.update();

		for (ListedCommonStock stock : stocks) {
			JdbcClient.StatementSpec statement = jdbcClient.sql("""
				UPDATE security
				SET market = :market, common_stock = TRUE, active = TRUE,
				    isin_code = COALESCE(:isinCode, isin_code),
				    master_updated_at = :now, updated_at = :now
				WHERE stock_code = :stockCode
				""")
				.param("market", stock.market().name())
				.param("now", now)
				.param("stockCode", stock.stockCode());
			statement = stock.isinCode() == null
				? statement.param("isinCode", null, java.sql.Types.CHAR)
				: statement.param("isinCode", stock.isinCode());
			int updated = statement.update();
			if (updated != 1) {
				throw new IllegalStateException("Stock master code is missing from OpenDART corporations");
			}
		}
	}

	@Override
	public Set<String> findActiveCommonStockCodes() {
		return new HashSet<>(jdbcClient.sql("""
			SELECT security.stock_code
			FROM security
			JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
			WHERE security.active AND security.common_stock
			  AND security.market IN ('KOSPI', 'KOSDAQ')
			""")
			.query(String.class)
			.list());
	}

	@Override
	@Transactional
	public boolean saveFiling(OpenDartFiling filing) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		return saveFiling(filing, now);
	}

	@Override
	@Transactional
	public int saveFilings(List<OpenDartFiling> filings) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		int saved = 0;
		for (OpenDartFiling filing : filings) {
			if (saveFiling(filing, now)) {
				saved++;
			}
		}
		return saved;
	}

	private boolean saveFiling(OpenDartFiling filing, OffsetDateTime now) {
		UUID issuerId = upsertIssuer(
			filing.corpCode(),
			filing.corporationName(),
			null,
			filing.corporationClass().code()
		);
		UUID securityId = filing.stockCode() == null
			? null
			: upsertSecurity(issuerId, filing.stockCode(), filing.corporationClass().market());
		String filingFamilyKey = filingFamilyKey(filing.reportName());
		String normalizedTitle = DisclosureTitlePolicy.normalize(filing.reportName());
		String titleSourceHash = DisclosureTitlePolicy.sourceHash(normalizedTitle);

		Optional<UUID> inserted = jdbcClient.sql("""
			INSERT INTO disclosure (
			    id, receipt_number, issuer_id, security_id, disclosure_type, title_ko,
			    title_source_hash,
			    submitter, filed_date, detected_at, official_url, remark, correction,
			    filing_family_key,
			    document_status, created_at, updated_at
			)
			VALUES (
			    :id, :receiptNumber, :issuerId, :securityId, :disclosureType, :titleKo,
			    :titleSourceHash,
			    :submitter, :filedDate, :detectedAt, :officialUrl, :remark, :correction,
			    :filingFamilyKey,
			    'PENDING', :createdAt, :updatedAt
			)
			ON CONFLICT (receipt_number) DO NOTHING
			RETURNING id
			""")
			.param("id", UUID.randomUUID())
			.param("receiptNumber", filing.receiptNumber())
			.param("issuerId", issuerId)
			.param("securityId", securityId)
			.param("disclosureType", filing.disclosureType().code())
			.param("titleKo", filing.reportName())
			.param("titleSourceHash", titleSourceHash)
			.param("submitter", filing.submitter())
			.param("filedDate", filing.filedDate())
			.param("detectedAt", now)
			.param("officialUrl", officialUrl(filing.receiptNumber()))
			.param("remark", filing.remark())
			.param("correction", isCorrection(filing))
			.param("filingFamilyKey", filingFamilyKey)
			.param("createdAt", now)
			.param("updatedAt", now)
			.query(UUID.class)
			.optional();

		if (inserted.isEmpty()) {
			jdbcClient.sql("""
				UPDATE disclosure
				SET issuer_id = :issuerId,
				    security_id = :securityId,
				    disclosure_type = :disclosureType,
				    title_ko = :titleKo,
				    title_source_hash = :titleSourceHash,
				    submitter = :submitter,
				    filed_date = :filedDate,
				    remark = :remark,
				    correction = :correction,
				    filing_family_key = :filingFamilyKey,
				    updated_at = :updatedAt
				WHERE receipt_number = :receiptNumber
				""")
				.param("issuerId", issuerId)
				.param("securityId", securityId)
				.param("disclosureType", filing.disclosureType().code())
				.param("titleKo", filing.reportName())
				.param("titleSourceHash", titleSourceHash)
				.param("submitter", filing.submitter())
				.param("filedDate", filing.filedDate())
				.param("remark", filing.remark())
				.param("correction", isCorrection(filing))
				.param("filingFamilyKey", filingFamilyKey)
				.param("updatedAt", now)
				.param("receiptNumber", filing.receiptNumber())
				.update();
			enqueueTitleTranslationIfSupported(
				filing.stockCode(), titleSourceHash, filing.reportName(), normalizedTitle, now
			);
			relinkCorrectionFamily(issuerId, filingFamilyKey);
			return false;
		}
		enqueueTitleTranslationIfSupported(
			filing.stockCode(), titleSourceHash, filing.reportName(), normalizedTitle, now
		);
		relinkCorrectionFamily(issuerId, filingFamilyKey);

		if (filing.stockCode() != null) {
			jdbcClient.sql("""
				INSERT INTO ingestion_job (
				    id, job_type, business_key, stock_code, status, attempts, priority,
				    available_at, created_at, updated_at
				)
				SELECT :id, :jobType, :businessKey, :stockCode, 'PENDING', 0,
				       CASE
				           WHEN :filedDate >= CURRENT_DATE - INTERVAL '1 year'
				             OR (
				                 :filedDate >= CURRENT_DATE - INTERVAL '5 years'
				                 AND :titleKo ~ '(사업|반기|분기)보고서'
				             ) THEN 10
				           ELSE 20
				       END,
				       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				FROM service_stock_universe universe
				WHERE universe.stock_code = :stockCode
				ON CONFLICT (job_type, business_key) DO NOTHING
				""")
				.param("id", UUID.randomUUID())
				.param("jobType", DOCUMENT_JOB)
				.param("businessKey", filing.receiptNumber())
				.param("filedDate", filing.filedDate())
				.param("titleKo", filing.reportName())
				.param("stockCode", filing.stockCode())
				.update();
			jdbcClient.sql("""
				INSERT INTO ingestion_job (
				    id, job_type, business_key, status, attempts,
				    available_at, created_at, updated_at
				)
				SELECT :id, :jobType, :businessKey, 'PENDING', 0,
				       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				FROM service_stock_universe universe
				WHERE universe.stock_code = :stockCode
				ON CONFLICT (job_type, business_key) DO NOTHING
				""")
				.param("id", UUID.randomUUID())
				.param("jobType", METADATA_EMBEDDING_JOB)
				.param("businessKey", filing.receiptNumber())
				.param("stockCode", filing.stockCode())
				.update();
		}
		return true;
	}

	@Override
	public Optional<DocumentJob> claimDocumentJob(String workerId) {
		return jdbcClient.sql("""
			WITH candidate AS (
			    SELECT job.id, security.stock_code, issuer.name_ko AS stock_name_ko
			    FROM ingestion_job job
			    JOIN disclosure disclosure ON disclosure.receipt_number = job.business_key
			    JOIN security security ON security.id = disclosure.security_id
			    JOIN issuer issuer ON issuer.id = security.issuer_id
			    JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
			    WHERE job.job_type = :jobType
			      AND security.active
			      AND security.common_stock
			      AND pg_database_size(current_database()) < 64424509440
			      AND NOT EXISTS (
			          SELECT 1
			          FROM ingestion_provider_throttle throttle
			          WHERE throttle.provider = :provider
			            AND throttle.blocked_until > CURRENT_TIMESTAMP
			      )
			      AND job.available_at <= CURRENT_TIMESTAMP
			      AND (
			          job.status = 'PENDING'
			          OR (
			              job.status = 'PROCESSING'
			              AND job.locked_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes'
			          )
			      )
			    ORDER BY job.priority, job.available_at, job.created_at
			    FOR UPDATE OF job SKIP LOCKED
			    LIMIT 1
			)
			UPDATE ingestion_job job
			SET status = 'PROCESSING',
			    attempts = attempts + 1,
			    locked_at = CURRENT_TIMESTAMP,
			    locked_by = :workerId,
			    updated_at = CURRENT_TIMESTAMP
			FROM candidate
			WHERE job.id = candidate.id
			RETURNING job.business_key, candidate.stock_code, candidate.stock_name_ko, job.attempts
			""")
			.param("jobType", DOCUMENT_JOB)
			.param("provider", OPEN_DART_DOCUMENT_PROVIDER)
			.param("workerId", workerId)
			.query((resultSet, rowNumber) -> new DocumentJob(
				resultSet.getString("business_key"),
				resultSet.getString("stock_code"),
				resultSet.getString("stock_name_ko"),
				resultSet.getInt("attempts")
			))
			.optional();
	}

	@Override
	public boolean isOpenDartDocumentCollectionBlocked() {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM ingestion_provider_throttle
			    WHERE provider = :provider
			      AND blocked_until > CURRENT_TIMESTAMP
			)
			""")
			.param("provider", OPEN_DART_DOCUMENT_PROVIDER)
			.query(Boolean.class)
			.single();
	}

	@Override
	public void blockOpenDartDocumentCollection(Duration delay, String reason) {
		jdbcClient.sql("""
			INSERT INTO ingestion_provider_throttle (
			    provider, blocked_until, reason, updated_at
			)
			VALUES (
			    :provider,
			    CURRENT_TIMESTAMP + (:delaySeconds * INTERVAL '1 second'),
			    :reason,
			    CURRENT_TIMESTAMP
			)
			ON CONFLICT (provider) DO UPDATE
			SET blocked_until = GREATEST(
			        ingestion_provider_throttle.blocked_until,
			        EXCLUDED.blocked_until
			    ),
			    reason = EXCLUDED.reason,
			    updated_at = CURRENT_TIMESTAMP
			""")
			.param("provider", OPEN_DART_DOCUMENT_PROVIDER)
			.param("delaySeconds", delay.toSeconds())
			.param("reason", reason.substring(0, Math.min(reason.length(), 100)))
			.update();
	}

	@Override
	@Transactional
	public void completeDocumentJob(
		String receiptNumber,
		List<OpenDartDocument> documents,
		List<StoredDocumentArchive> archives
	) {
		if (documents.isEmpty()) {
			throw new IllegalArgumentException("Disclosure documents must not be empty");
		}
		UUID disclosureId = disclosureId(receiptNumber);
		jdbcClient.sql("SELECT id FROM disclosure WHERE id = :id FOR UPDATE")
			.param("id", disclosureId).query(UUID.class).single();
		jdbcClient.sql("""
			UPDATE disclosure_document
			SET is_current = FALSE
			WHERE disclosure_id = :disclosureId AND is_current = TRUE
			""")
			.param("disclosureId", disclosureId)
			.update();

		for (OpenDartDocument document : documents) {
			DisclosurePayloadCodec.EncodedPayload payload = payloadCodec.encode(document);
			UUID documentId = jdbcClient.sql("""
				INSERT INTO disclosure_document (
				    id, disclosure_id, source_filename, version_no, is_current,
				    content_hash, body_text, payload_zstd, original_bytes,
				    compressed_bytes, parser_version, created_at
				)
				SELECT :id, :disclosureId, :sourceFilename,
				       COALESCE(MAX(version_no), 0) + 1, TRUE,
				       :contentHash, NULL, :payloadZstd, :originalBytes,
				       :compressedBytes, :parserVersion, :createdAt
				FROM disclosure_document
				WHERE disclosure_id = :disclosureId AND source_filename = :sourceFilename
				ON CONFLICT (disclosure_id, source_filename, content_hash, parser_version)
				DO UPDATE SET is_current = TRUE
				RETURNING id
				""")
				.param("id", UUID.randomUUID())
				.param("disclosureId", disclosureId)
				.param("sourceFilename", document.filename())
				.param("contentHash", document.contentHash())
				.param("payloadZstd", payload.compressed())
				.param("originalBytes", payload.originalBytes())
				.param("compressedBytes", payload.compressedBytes())
				.param("parserVersion", DOCUMENT_PARSER_VERSION)
				.param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
				.query(UUID.class)
				.single();

			jdbcClient.sql("DELETE FROM disclosure_section WHERE document_id = :documentId")
				.param("documentId", documentId)
				.update();
		}
		saveDocumentArchives(disclosureId, receiptNumber, archives);

		jdbcClient.sql("""
			UPDATE disclosure
			SET document_status = 'READY', index_status = 'PENDING', updated_at = CURRENT_TIMESTAMP
			WHERE id = :disclosureId
			""")
			.param("disclosureId", disclosureId)
			.update();
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'COMPLETED', locked_at = NULL, locked_by = NULL,
			    updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.update();
		jdbcClient.sql("""
			INSERT INTO ingestion_job (
			    id, job_type, business_key, status, attempts,
			    available_at, created_at, updated_at
			)
			SELECT
			    :id, :jobType, :businessKey, 'PENDING', 0,
			    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			FROM disclosure
			WHERE disclosure.id = :disclosureId
			  AND (
			      disclosure.filed_date >= CURRENT_DATE - INTERVAL '1 year'
			      OR (
			          disclosure.filed_date >= CURRENT_DATE - INTERVAL '5 years'
			          AND disclosure.title_ko ~ '(사업|반기|분기)보고서'
			      )
			      OR EXISTS (
			          SELECT 1
			          FROM ingestion_job document_job
			          WHERE document_job.job_type = :documentJobType
			            AND document_job.business_key = :businessKey
			            AND document_job.last_error_code = 'ON_DEMAND'
			      )
			  )
			ON CONFLICT (job_type, business_key) DO UPDATE
			SET status = 'PENDING', attempts = 0, available_at = CURRENT_TIMESTAMP,
			    locked_at = NULL, locked_by = NULL, last_error_code = NULL,
			    updated_at = CURRENT_TIMESTAMP
			""")
			.param("id", UUID.randomUUID())
			.param("jobType", EMBEDDING_JOB)
			.param("documentJobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.param("disclosureId", disclosureId)
			.update();
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.update();
		jdbcClient.sql("""
			INSERT INTO ingestion_job (
			    id, job_type, business_key, status, attempts,
			    available_at, created_at, updated_at
			)
			VALUES (
			    :id, :jobType, :businessKey, 'PENDING', 0,
			    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			)
			ON CONFLICT (job_type, business_key) DO UPDATE
			SET status = 'PENDING', attempts = 0, available_at = CURRENT_TIMESTAMP,
			    locked_at = NULL, locked_by = NULL, last_error_code = NULL,
			    updated_at = CURRENT_TIMESTAMP
			""")
			.param("id", UUID.randomUUID())
			.param("jobType", SIGNAL_JOB)
			.param("businessKey", receiptNumber)
			.update();
	}

	@Override
	@Transactional
	public void recordDocumentArchives(String receiptNumber, List<StoredDocumentArchive> archives) {
		if (archives.isEmpty()) {
			return;
		}
		saveDocumentArchives(disclosureId(receiptNumber), receiptNumber, archives);
	}

	private void saveDocumentArchives(
		UUID disclosureId,
		String receiptNumber,
		List<StoredDocumentArchive> archives
	) {
		for (StoredDocumentArchive archive : archives) {
			jdbcClient.sql("""
				INSERT INTO disclosure_archive (
				    id, disclosure_id, receipt_number, stock_code, stock_name_ko,
				    archive_kind, archive_status, relative_path, sha256, size_bytes,
				    error_code, created_at, updated_at
				)
				VALUES (
				    :id, :disclosureId, :receiptNumber, :stockCode, :stockNameKo,
				    :archiveKind, :archiveStatus, :relativePath, :sha256, :sizeBytes,
				    :errorCode, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
				)
				ON CONFLICT (disclosure_id, archive_kind)
				DO UPDATE SET
				    receipt_number = EXCLUDED.receipt_number,
				    stock_code = EXCLUDED.stock_code,
				    stock_name_ko = EXCLUDED.stock_name_ko,
				    archive_status = EXCLUDED.archive_status,
				    relative_path = EXCLUDED.relative_path,
				    sha256 = EXCLUDED.sha256,
				    size_bytes = EXCLUDED.size_bytes,
				    error_code = EXCLUDED.error_code,
				    updated_at = CURRENT_TIMESTAMP
				""")
				.param("id", UUID.randomUUID())
				.param("disclosureId", disclosureId)
				.param("receiptNumber", receiptNumber)
				.param("stockCode", stockCode(receiptNumber))
				.param("stockNameKo", stockName(receiptNumber))
				.param("archiveKind", archive.kind().name())
				.param("archiveStatus", archive.status().name())
				.param("relativePath", archive.relativePath())
				.param("sha256", archive.sha256())
				.param("sizeBytes", archive.sizeBytes())
				.param("errorCode", archive.errorCode())
				.update();
		}
	}

	@Override
	@Transactional
	public void retryDocumentJob(String receiptNumber, String errorCode, Duration delay) {
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'PENDING',
			    priority = CASE
			        WHEN :errorCode = 'DART_VIEWER_NETWORK_ERROR' THEN 30
			        ELSE priority
			    END,
			    available_at = CURRENT_TIMESTAMP + (:delayMillis * INTERVAL '1 millisecond'),
			    locked_at = NULL,
			    locked_by = NULL,
			    last_error_code = :errorCode,
			    updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("delayMillis", delay.toMillis())
			.param("errorCode", abbreviate(errorCode, 100))
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.update();
	}

	@Override
	@Transactional
	public void failDocumentJob(String receiptNumber, String errorCode) {
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'FAILED', locked_at = NULL, locked_by = NULL,
			    last_error_code = :errorCode, updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("errorCode", abbreviate(errorCode, 100))
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.update();
		jdbcClient.sql("""
			UPDATE disclosure
			SET document_status = 'FAILED', index_status = 'FAILED', updated_at = CURRENT_TIMESTAMP
			WHERE receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.update();
	}

	@Override
	@Transactional
	public void markDocumentUnavailable(String receiptNumber, String errorCode) {
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'COMPLETED', locked_at = NULL, locked_by = NULL,
			    last_error_code = :errorCode, updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("errorCode", abbreviate(errorCode, 100))
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", receiptNumber)
			.update();
		jdbcClient.sql("""
			UPDATE disclosure
			SET document_status = 'UNAVAILABLE', index_status = 'UNAVAILABLE',
			    updated_at = CURRENT_TIMESTAMP
			WHERE receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.update();
	}

	@Override
	@Transactional
	public Optional<DisclosureSignalJob> claimSignalJob(String workerId) {
		Optional<SignalClaim> claim = jdbcClient.sql("""
			WITH candidate AS (
			    SELECT job.id
			    FROM ingestion_job job
			    WHERE job.job_type = :jobType
			      AND job.available_at <= CURRENT_TIMESTAMP
			      AND (
			          job.status = 'PENDING'
			          OR (job.status = 'PROCESSING'
			              AND job.locked_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
			      )
			    ORDER BY job.business_key DESC
			    FOR UPDATE OF job SKIP LOCKED
			    LIMIT 1
			)
			UPDATE ingestion_job job
			SET status = 'PROCESSING', attempts = attempts + 1,
			    locked_at = CURRENT_TIMESTAMP, locked_by = :workerId,
			    updated_at = CURRENT_TIMESTAMP
			FROM candidate
			WHERE job.id = candidate.id
			RETURNING job.business_key, job.attempts
			""")
			.param("jobType", SIGNAL_JOB)
			.param("workerId", workerId)
			.query((resultSet, rowNumber) -> new SignalClaim(
				resultSet.getString("business_key"), resultSet.getInt("attempts")
			))
			.optional();
		return claim.map(this::loadSignalJob);
	}

	@Override
	@Transactional
	public void completeSignalJob(String receiptNumber, NewsAnalysis analysis) {
		jdbcClient.sql("""
			UPDATE disclosure
			SET event_type = :eventType, sentiment = :sentiment, importance = :importance,
			    market_impact = :marketImpact,
			    market_impact_importance = :marketImpactImportance,
			    market_impact_score = :marketImpactScore,
			    event_confidence = :eventConfidence,
			    sentiment_confidence = :sentimentConfidence,
			    importance_confidence = :importanceConfidence,
			    market_impact_confidence = :marketImpactConfidence,
			    analysis_status = 'READY', analysis_model_id = :modelId,
			    analyzed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
			WHERE receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.param("eventType", analysis.eventType())
			.param("sentiment", analysis.sentiment().name())
			.param("importance", analysis.importance().name())
			.param("marketImpact", analysis.marketImpact().name())
			.param("marketImpactImportance", analysis.marketImpactImportance().name())
			.param("marketImpactScore", analysis.marketImpactScore())
			.param("eventConfidence", analysis.eventConfidence())
			.param("sentimentConfidence", analysis.sentimentConfidence())
			.param("importanceConfidence", analysis.importanceConfidence())
			.param("marketImpactConfidence", analysis.marketImpactConfidence())
			.param("modelId", analysis.model())
			.update();
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'COMPLETED', locked_at = NULL, locked_by = NULL,
			    last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("jobType", SIGNAL_JOB)
			.param("businessKey", receiptNumber)
			.update();
	}

	@Override
	@Transactional
	public void retrySignalJob(String receiptNumber, String errorCode, Duration delay) {
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = CASE WHEN attempts >= 5 THEN 'FAILED' ELSE 'PENDING' END,
			    available_at = CURRENT_TIMESTAMP + (:delayMillis * INTERVAL '1 millisecond'),
			    locked_at = NULL, locked_by = NULL, last_error_code = :errorCode,
			    updated_at = CURRENT_TIMESTAMP
			WHERE job_type = :jobType AND business_key = :businessKey
			""")
			.param("delayMillis", delay.toMillis())
			.param("errorCode", abbreviate(errorCode, 100))
			.param("jobType", SIGNAL_JOB)
			.param("businessKey", receiptNumber)
			.update();
		jdbcClient.sql("""
			UPDATE disclosure
			SET analysis_status = CASE
			        WHEN (SELECT attempts FROM ingestion_job
			              WHERE job_type = :jobType AND business_key = :businessKey) >= 5
			          THEN 'FAILED' ELSE 'PENDING' END,
			    updated_at = CURRENT_TIMESTAMP
			WHERE receipt_number = :businessKey
			""")
			.param("jobType", SIGNAL_JOB)
			.param("businessKey", receiptNumber)
			.update();
	}

	@Override
	public List<DisclosureSummary> findAll(DisclosureListQuery query, int fetchSize) {
		StringBuilder sql = new StringBuilder("""
			SELECT d.receipt_number, i.dart_corp_code, i.name_ko, i.name_en,
			       s.stock_code, COALESCE(s.market, 'UNKNOWN') AS market,
			       d.disclosure_type, d.title_ko,
			       CASE WHEN translation.status = 'READY' THEN translation.translated_text END AS title_en,
			       d.event_type, d.sentiment, d.importance, d.market_impact,
			       d.filed_date, COUNT(*) OVER (PARTITION BY d.filed_date) AS filed_date_total,
			       d.detected_at, d.correction, d.document_status, d.index_status, d.official_url
			FROM disclosure d
			JOIN issuer i ON i.id = d.issuer_id
			JOIN security s ON s.id = d.security_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			LEFT JOIN translation_memory translation
			  ON translation.content_kind = 'DISCLOSURE_TITLE'
			 AND translation.source_hash = d.title_source_hash
			 AND translation.target_locale = 'en'
			 AND translation.translation_version = :translationVersion
			WHERE s.active AND s.common_stock
			  AND translation.status = 'READY'
			""");
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("translationVersion", DisclosureTitlePolicy.TRANSLATION_VERSION);
		appendFilters(sql, parameters, query);
		sql.append(" ORDER BY d.filed_date DESC, d.receipt_number DESC LIMIT :fetchSize");
		parameters.put("fetchSize", fetchSize);

		JdbcClient.StatementSpec statement = jdbcClient.sql(sql.toString());
		for (var parameter : parameters.entrySet()) {
			statement = statement.param(parameter.getKey(), parameter.getValue());
		}
		return statement.query(this::mapSummary).list();
	}

	@Override
	public Optional<DisclosureDetail> findByReceiptNumber(String receiptNumber) {
		Optional<DisclosureDetailRow> detail = jdbcClient.sql("""
			SELECT d.id, d.issuer_id, d.filing_family_key, d.receipt_number,
			       i.dart_corp_code, i.name_ko, i.name_en,
			       s.stock_code, COALESCE(s.market, 'UNKNOWN') AS market,
			       d.disclosure_type, d.title_ko,
			       translation.translated_text AS title_en,
			       d.event_type, d.sentiment, d.importance, d.market_impact,
			       d.submitter,
			       d.filed_date, d.detected_at, d.remark, d.correction,
			       d.document_status, d.index_status, d.official_url
			FROM disclosure d
			JOIN issuer i ON i.id = d.issuer_id
			JOIN security s ON s.id = d.security_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			LEFT JOIN translation_memory translation
			  ON translation.content_kind = 'DISCLOSURE_TITLE'
			 AND translation.source_hash = d.title_source_hash
			 AND translation.target_locale = 'en'
			 AND translation.translation_version = :translationVersion
			WHERE d.receipt_number = :receiptNumber
			  AND s.active AND s.common_stock
			""")
			.param("receiptNumber", receiptNumber)
			.param("translationVersion", DisclosureTitlePolicy.TRANSLATION_VERSION)
			.query(this::mapDetailRow)
			.optional();
		return detail.map(this::withDocuments);
	}

	@Override
	public Optional<DisclosureInsight> findInsight(
		String receiptNumber,
		String contentVersionHash
	) {
		return jdbcClient.sql("""
			SELECT disclosure.receipt_number, insight.content_version_hash,
			       insight.what_summary, insight.why_summary, insight.impact_summary,
			       insight.source_section_ids, insight.sufficient_evidence,
			       insight.refusal_reason, insight.model_id, insight.prompt_version,
			       insight.generated_at, insight.what_summary_ko, insight.why_summary_ko,
			       insight.impact_summary_ko
			FROM disclosure_ai_summary insight
			JOIN disclosure ON disclosure.id = insight.disclosure_id
			WHERE disclosure.receipt_number = :receiptNumber
			  AND insight.content_version_hash = :contentVersionHash
			""")
			.param("receiptNumber", receiptNumber)
			.param("contentVersionHash", contentVersionHash)
			.query((resultSet, rowNumber) -> new DisclosureInsight(
				resultSet.getString("receipt_number"),
				resultSet.getString("content_version_hash"),
				resultSet.getString("what_summary"),
				resultSet.getString("why_summary"),
				resultSet.getString("impact_summary"),
				uuidArray(resultSet, "source_section_ids"),
				resultSet.getBoolean("sufficient_evidence"),
				resultSet.getString("refusal_reason"),
				resultSet.getString("model_id"),
				resultSet.getString("prompt_version"),
				resultSet.getObject("generated_at", OffsetDateTime.class).toInstant(),
				resultSet.getString("what_summary_ko"),
				resultSet.getString("why_summary_ko"),
				resultSet.getString("impact_summary_ko")
			))
			.optional();
	}

	@Override
	public void saveInsight(DisclosureInsight insight) {
		if (insight.sufficientEvidence()) {
			EnglishTextPolicy.requireValid(insight.what());
			EnglishTextPolicy.requireValid(insight.why());
			EnglishTextPolicy.requireValid(insight.impact());
			for (String korean : java.util.Arrays.asList(
				insight.whatKo(), insight.whyKo(), insight.impactKo()
			)) {
				if (korean == null || !korean.matches("(?s).*[가-힣].*")) {
					throw new IllegalArgumentException("Korean summary is incomplete");
				}
			}
		}
		else if (insight.refusalReason() != null) {
			EnglishTextPolicy.requireValid(insight.refusalReason());
		}
		String sourceIds = insight.sourceSectionIds().stream()
			.map(UUID::toString)
			.collect(java.util.stream.Collectors.joining(","));
		JdbcClient.StatementSpec statement = jdbcClient.sql("""
			INSERT INTO disclosure_ai_summary (
			    disclosure_id, content_version_hash, what_summary, why_summary,
			    impact_summary, source_section_ids, sufficient_evidence,
			    refusal_reason, model_id, prompt_version, generated_at,
			    what_summary_ko, why_summary_ko, impact_summary_ko
			)
			SELECT disclosure.id, :contentVersionHash, :whatSummary, :whySummary,
			       :impactSummary,
			       CASE WHEN CAST(:sourceIds AS varchar) = '' THEN ARRAY[]::uuid[]
			            ELSE string_to_array(:sourceIds, ',')::uuid[] END,
			       :sufficientEvidence, :refusalReason, :modelId, :promptVersion,
			       :generatedAt, :whatKo, :whyKo, :impactKo
			FROM disclosure
			WHERE disclosure.receipt_number = :receiptNumber
			ON CONFLICT (disclosure_id) DO UPDATE
			SET content_version_hash = EXCLUDED.content_version_hash,
			    what_summary = EXCLUDED.what_summary,
			    why_summary = EXCLUDED.why_summary,
			    impact_summary = EXCLUDED.impact_summary,
			    source_section_ids = EXCLUDED.source_section_ids,
			    sufficient_evidence = EXCLUDED.sufficient_evidence,
			    refusal_reason = EXCLUDED.refusal_reason,
			    model_id = EXCLUDED.model_id,
			    prompt_version = EXCLUDED.prompt_version,
			    generated_at = EXCLUDED.generated_at,
			    what_summary_ko = EXCLUDED.what_summary_ko,
			    why_summary_ko = EXCLUDED.why_summary_ko,
			    impact_summary_ko = EXCLUDED.impact_summary_ko
			""")
			.param("receiptNumber", insight.receiptNumber())
			.param("contentVersionHash", insight.contentVersionHash())
			.param("sourceIds", sourceIds)
			.param("sufficientEvidence", insight.sufficientEvidence())
			.param("modelId", insight.modelId())
			.param("promptVersion", insight.promptVersion())
			.param("generatedAt", insight.generatedAt().atOffset(ZoneOffset.UTC))
			.param("whatSummary", insight.what(), java.sql.Types.VARCHAR)
			.param("whySummary", insight.why(), java.sql.Types.VARCHAR)
			.param("impactSummary", insight.impact(), java.sql.Types.VARCHAR)
			.param("whatKo", insight.whatKo(), java.sql.Types.VARCHAR)
			.param("whyKo", insight.whyKo(), java.sql.Types.VARCHAR)
			.param("impactKo", insight.impactKo(), java.sql.Types.VARCHAR)
			.param("refusalReason", insight.refusalReason(), java.sql.Types.VARCHAR);
		statement.update();
	}

	@Override
	public Optional<IndexStatus> findIndexStatus(String receiptNumber) {
		return jdbcClient.sql("""
			SELECT disclosure.index_status
			FROM disclosure disclosure
			JOIN security security ON security.id = disclosure.security_id
			JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
			WHERE disclosure.receipt_number = :receiptNumber
			  AND security.active AND security.common_stock
			""")
			.param("receiptNumber", receiptNumber)
			.query(String.class)
			.optional()
			.map(IndexStatus::valueOf);
	}

	@Override
	@Transactional
	public boolean requestIndexing(String receiptNumber) {
		Optional<DocumentStatus> status = jdbcClient.sql("""
			SELECT disclosure.document_status
			FROM disclosure
			JOIN security ON security.id = disclosure.security_id
			JOIN service_stock_universe universe ON universe.stock_code = security.stock_code
			WHERE disclosure.receipt_number = :receiptNumber
			  AND security.active AND security.common_stock
			""")
			.param("receiptNumber", receiptNumber)
			.query(String.class)
			.optional()
			.map(DocumentStatus::valueOf);
		if (status.isEmpty()) {
			return false;
		}
		String jobType = status.get() == DocumentStatus.READY ? EMBEDDING_JOB : DOCUMENT_JOB;
		jdbcClient.sql("""
			INSERT INTO ingestion_job (
			    id, job_type, business_key, stock_code, status, attempts, priority,
			    available_at, last_error_code, created_at, updated_at
			)
			VALUES (
			    :id, :jobType, :businessKey,
			    (
			        SELECT security.stock_code
			        FROM disclosure
			        JOIN security ON security.id = disclosure.security_id
			        WHERE disclosure.receipt_number = :businessKey
			    ),
			    'PENDING', 0, 0,
			    CURRENT_TIMESTAMP, 'ON_DEMAND', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			)
			ON CONFLICT (job_type, business_key) DO UPDATE
			SET stock_code = COALESCE(ingestion_job.stock_code, EXCLUDED.stock_code),
			    status = 'PENDING', attempts = 0, priority = 0, available_at = CURRENT_TIMESTAMP,
			    locked_at = NULL, locked_by = NULL, last_error_code = 'ON_DEMAND',
			    updated_at = CURRENT_TIMESTAMP
			""")
			.param("id", UUID.randomUUID())
			.param("jobType", jobType)
			.param("businessKey", receiptNumber)
			.update();
		return true;
	}

	private DisclosureDetail withDocuments(DisclosureDetailRow row) {
		List<DocumentRow> rows = jdbcClient.sql("""
			SELECT id, source_filename, version_no, content_hash, payload_zstd
			FROM disclosure_document
			WHERE disclosure_id = :disclosureId AND is_current = TRUE
			ORDER BY created_at, source_filename
			""")
			.param("disclosureId", row.id())
			.query((resultSet, rowNumber) -> new DocumentRow(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("source_filename"),
				resultSet.getInt("version_no"),
				resultSet.getString("content_hash"),
				resultSet.getBytes("payload_zstd")
			))
			.list();
		List<DisclosureDocument> documents = rows.stream().map(document -> {
			if (document.payload() == null) {
				return new DisclosureDocument(
					document.id(), document.sourceFilename(), document.version(),
					document.contentHash(), null, findSections(document.id())
				);
			}
			DisclosurePayloadCodec.DecodedPayload payload = payloadCodec.decodePayload(document.payload());
			return new DisclosureDocument(
				document.id(), document.sourceFilename(), document.version(),
				document.contentHash(), payload.sanitizedHtml(), payload.sections()
			);
		}).toList();
		List<DisclosureVersion> versions = jdbcClient.sql("""
			SELECT version.receipt_number, version.title_ko, version.filed_date,
			       version.correction,
			       predecessor.receipt_number AS correction_of_receipt_number
			FROM disclosure version
			LEFT JOIN disclosure predecessor ON predecessor.id = version.correction_of_id
			WHERE version.issuer_id = :issuerId
			  AND version.filing_family_key = :filingFamilyKey
			ORDER BY version.receipt_number
			""")
			.param("issuerId", row.issuerId())
			.param("filingFamilyKey", row.filingFamilyKey())
			.query((resultSet, rowNumber) -> new DisclosureVersion(
				resultSet.getString("receipt_number"),
				resultSet.getString("title_ko"),
				resultSet.getObject("filed_date", java.time.LocalDate.class),
				resultSet.getBoolean("correction"),
				resultSet.getString("correction_of_receipt_number"),
				row.receiptNumber().equals(resultSet.getString("receipt_number"))
			))
			.list();
		return row.toDetail(documents, versions);
	}

	private void relinkCorrectionFamily(UUID issuerId, String filingFamilyKey) {
		jdbcClient.sql("""
			WITH ordered_versions AS (
			    SELECT id, correction,
			           lag(id) OVER (ORDER BY receipt_number) AS previous_id
			    FROM disclosure
			    WHERE issuer_id = :issuerId
			      AND filing_family_key = :filingFamilyKey
			)
			UPDATE disclosure target
			SET correction_of_id = ordered_versions.previous_id
			FROM ordered_versions
			WHERE target.id = ordered_versions.id
			  AND ordered_versions.correction
			""")
			.param("issuerId", issuerId)
			.param("filingFamilyKey", filingFamilyKey)
			.update();
	}

	private String stockCode(String receiptNumber) {
		return jdbcClient.sql("""
			SELECT security.stock_code
			FROM disclosure
			JOIN security ON security.id = disclosure.security_id
			WHERE disclosure.receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.query(String.class)
			.single();
	}

	private String stockName(String receiptNumber) {
		return jdbcClient.sql("""
			SELECT issuer.name_ko
			FROM disclosure
			JOIN issuer ON issuer.id = disclosure.issuer_id
			WHERE disclosure.receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.query(String.class)
			.single();
	}

	private List<DisclosureSection> findSections(UUID documentId) {
		return jdbcClient.sql("""
			SELECT id, ordinal, section_kind, heading, text_content, table_data::TEXT AS table_data
			FROM disclosure_section
			WHERE document_id = :documentId
			ORDER BY ordinal
			""")
			.param("documentId", documentId)
			.query((resultSet, rowNumber) -> new DisclosureSection(
				resultSet.getObject("id", UUID.class),
				resultSet.getInt("ordinal"),
				SectionKind.valueOf(resultSet.getString("section_kind")),
				resultSet.getString("heading"),
				resultSet.getString("text_content"),
				resultSet.getString("table_data")
			))
			.list();
	}

	private DisclosureSignalJob loadSignalJob(SignalClaim claim) {
		SignalSource source = jdbcClient.sql("""
			SELECT disclosure.title_ko, issuer.name_ko, issuer.name_en
			FROM disclosure
			JOIN issuer ON issuer.id = disclosure.issuer_id
			WHERE disclosure.receipt_number = :receiptNumber
			""")
			.param("receiptNumber", claim.receiptNumber())
			.query((resultSet, rowNumber) -> new SignalSource(
				resultSet.getString("title_ko"),
				resultSet.getString("name_ko"),
				resultSet.getString("name_en")
			))
			.single();
		List<byte[]> payloads = jdbcClient.sql("""
			SELECT payload_zstd
			FROM disclosure_document document
			JOIN disclosure ON disclosure.id = document.disclosure_id
			WHERE disclosure.receipt_number = :receiptNumber
			  AND document.is_current = TRUE
			  AND document.payload_zstd IS NOT NULL
			ORDER BY document.created_at, document.source_filename
			""")
			.param("receiptNumber", claim.receiptNumber())
			.query(byte[].class)
			.list();
		List<String> paragraphs = new ArrayList<>();
		int totalChars = 0;
		for (byte[] payload : payloads) {
			for (DisclosureSection section : payloadCodec.decode(payload)) {
				String value = String.join("\n",
					java.util.stream.Stream.of(section.heading(), section.text(), section.tableData())
						.filter(java.util.Objects::nonNull)
						.filter(text -> !text.isBlank())
						.toList()
				).strip();
				if (value.isBlank()) {
					continue;
				}
				String bounded = value.substring(0, Math.min(value.length(), 12_000));
				if (totalChars + bounded.length() > 120_000 || paragraphs.size() >= 200) {
					break;
				}
				paragraphs.add(bounded);
				totalChars += bounded.length();
			}
			if (totalChars >= 120_000 || paragraphs.size() >= 200) {
				break;
			}
		}
		if (paragraphs.isEmpty()) {
			paragraphs.add(source.title());
		}
		List<String> companies = java.util.stream.Stream.of(source.nameKo(), source.nameEn())
			.filter(java.util.Objects::nonNull)
			.filter(value -> !value.isBlank())
			.toList();
		return new DisclosureSignalJob(
			claim.receiptNumber(), source.title(), paragraphs, companies, claim.attempts()
		);
	}

	private UUID upsertIssuer(String corpCode, String nameKo, String nameEn, String corporationClass) {
		return jdbcClient.sql("""
			INSERT INTO issuer (
			    id, dart_corp_code, name_ko, name_en, corporation_class, created_at, updated_at
			)
			VALUES (:id, :corpCode, :nameKo, :nameEn, :corporationClass, :now, :now)
			ON CONFLICT (dart_corp_code) DO UPDATE
			SET name_ko = EXCLUDED.name_ko,
			    name_en = COALESCE(EXCLUDED.name_en, issuer.name_en),
			    corporation_class = COALESCE(EXCLUDED.corporation_class, issuer.corporation_class),
			    updated_at = EXCLUDED.updated_at
			RETURNING id
			""")
			.param("id", UUID.randomUUID())
			.param("corpCode", corpCode)
			.param("nameKo", nameKo)
			.param("nameEn", nameEn)
			.param("corporationClass", corporationClass)
			.param("now", OffsetDateTime.now(ZoneOffset.UTC))
			.query(UUID.class)
			.single();
	}

	private UUID upsertSecurity(UUID issuerId, String stockCode, Market market) {
		return jdbcClient.sql("""
			INSERT INTO security (id, issuer_id, stock_code, market, created_at, updated_at)
			VALUES (:id, :issuerId, :stockCode, :market, :now, :now)
			ON CONFLICT (stock_code) DO UPDATE
			SET issuer_id = EXCLUDED.issuer_id,
			    market = CASE
			        WHEN security.active AND security.common_stock THEN security.market
			        WHEN EXCLUDED.market = 'UNKNOWN' THEN security.market
			        ELSE EXCLUDED.market
			    END,
			    updated_at = EXCLUDED.updated_at
			RETURNING id
			""")
			.param("id", UUID.randomUUID())
			.param("issuerId", issuerId)
			.param("stockCode", stockCode)
			.param("market", market.name())
			.param("now", OffsetDateTime.now(ZoneOffset.UTC))
			.query(UUID.class)
			.single();
	}

	private UUID disclosureId(String receiptNumber) {
		return jdbcClient.sql("SELECT id FROM disclosure WHERE receipt_number = :receiptNumber")
			.param("receiptNumber", receiptNumber)
			.query(UUID.class)
			.optional()
			.orElseThrow(() -> new IllegalStateException("Disclosure does not exist"));
	}

	private void enqueueTitleTranslationIfSupported(
		String stockCode,
		String sourceHash,
		String sourceTitle,
		String normalizedTitle,
		OffsetDateTime now
	) {
		if (stockCode == null || !isSupportedStock(stockCode)) {
			return;
		}
		UUID translationId = jdbcClient.sql("""
			INSERT INTO translation_memory (
			    id, content_kind, source_locale, target_locale, translation_version,
			    source_hash, source_text, normalized_source_text, status,
			    created_at, updated_at
			)
			VALUES (
			    :id, :contentKind, :sourceLocale, :targetLocale, :translationVersion,
			    :sourceHash, :sourceTitle, :normalizedTitle, 'PENDING', :now, :now
			)
			ON CONFLICT (content_kind, source_hash, target_locale, translation_version)
			DO UPDATE SET
			    source_text = EXCLUDED.source_text,
			    normalized_source_text = EXCLUDED.normalized_source_text,
			    updated_at = CASE
			        WHEN translation_memory.status = 'READY' THEN translation_memory.updated_at
			        ELSE EXCLUDED.updated_at
			    END
			RETURNING id
			""")
			.param("id", UUID.randomUUID())
			.param("contentKind", DisclosureTitlePolicy.CONTENT_KIND)
			.param("sourceLocale", DisclosureTitlePolicy.SOURCE_LOCALE)
			.param("targetLocale", DisclosureTitlePolicy.TARGET_LOCALE)
			.param("translationVersion", DisclosureTitlePolicy.TRANSLATION_VERSION)
			.param("sourceHash", sourceHash)
			.param("sourceTitle", sourceTitle)
			.param("normalizedTitle", normalizedTitle)
			.param("now", now)
			.query(UUID.class)
			.single();

		jdbcClient.sql("""
			INSERT INTO translation_job (
			    translation_memory_id, status, attempts, available_at, created_at, updated_at
			)
			SELECT :translationId, 'PENDING', 0, :now, :now, :now
			FROM translation_memory memory
			WHERE memory.id = :translationId AND memory.status <> 'READY'
			ON CONFLICT (translation_memory_id) DO NOTHING
			""")
			.param("translationId", translationId)
			.param("now", now)
			.update();
	}

	private boolean isSupportedStock(String stockCode) {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM service_stock_universe
			    WHERE stock_code = :stockCode
			)
			""")
			.param("stockCode", stockCode)
			.query(Boolean.class)
			.single();
	}

	private void appendFilters(
		StringBuilder sql,
		Map<String, Object> parameters,
		DisclosureListQuery query
	) {
		if (query.query() != null) {
			sql.append("""
				 AND (
				     d.title_ko ILIKE '%' || :query || '%' ESCAPE '\\'
				     OR COALESCE(translation.translated_text, '') ILIKE '%' || :query || '%' ESCAPE '\\'
				     OR i.name_ko ILIKE '%' || :query || '%' ESCAPE '\\'
				     OR COALESCE(i.name_en, '') ILIKE '%' || :query || '%' ESCAPE '\\'
				     OR s.stock_code ILIKE '%' || :query || '%' ESCAPE '\\'
				 )
				""");
			parameters.put("query", escapeLike(query.query()));
		}
		if (query.stockCode() != null) {
			sql.append(" AND s.stock_code = :stockCode");
			parameters.put("stockCode", query.stockCode());
		}
		if (query.from() != null) {
			sql.append(" AND d.filed_date >= :fromDate");
			parameters.put("fromDate", query.from());
		}
		if (query.to() != null) {
			sql.append(" AND d.filed_date <= :toDate");
			parameters.put("toDate", query.to());
		}
		if (query.types() != null && !query.types().isEmpty()) {
			List<String> names = new ArrayList<>();
			int index = 0;
			for (DisclosureType type : query.types()) {
				String name = "type" + index++;
				names.add(":" + name);
				parameters.put(name, type.code());
			}
			sql.append(" AND d.disclosure_type IN (").append(String.join(", ", names)).append(')');
		}
		if (query.correction() != null) {
			sql.append(" AND d.correction = :correction");
			parameters.put("correction", query.correction());
		}
		if (query.cursor() != null) {
			sql.append("""
				 AND (
				     d.filed_date < :cursorDate
				     OR (d.filed_date = :cursorDate AND d.receipt_number < :cursorReceiptNumber)
				 )
				""");
			parameters.put("cursorDate", query.cursor().filedDate());
			parameters.put("cursorReceiptNumber", query.cursor().receiptNumber());
		}
	}

	private String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private DisclosureSummary mapSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DisclosureSummary(
			resultSet.getString("receipt_number"),
			resultSet.getString("dart_corp_code"),
			resultSet.getString("name_ko"),
			resultSet.getString("name_en"),
			resultSet.getString("stock_code"),
			Market.valueOf(resultSet.getString("market")),
			DisclosureType.fromCode(resultSet.getString("disclosure_type")),
			resultSet.getString("title_ko"),
			resultSet.getString("title_en"),
			resultSet.getString("event_type"),
			enumValue(com.kmarket.navigator.backend.news.domain.NewsSentiment.class,
				resultSet.getString("sentiment")),
			enumValue(com.kmarket.navigator.backend.news.domain.NewsImportance.class,
				resultSet.getString("importance")),
			enumValue(com.kmarket.navigator.backend.news.domain.MarketImpact.class,
				resultSet.getString("market_impact")),
			resultSet.getObject("filed_date", java.time.LocalDate.class),
			resultSet.getLong("filed_date_total"),
			resultSet.getObject("detected_at", OffsetDateTime.class).toInstant(),
			resultSet.getBoolean("correction"),
			DocumentStatus.valueOf(resultSet.getString("document_status")),
			IndexStatus.valueOf(resultSet.getString("index_status")),
			resultSet.getString("official_url")
		);
	}

	private DisclosureDetailRow mapDetailRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DisclosureDetailRow(
			resultSet.getObject("id", UUID.class),
			resultSet.getObject("issuer_id", UUID.class),
			resultSet.getString("filing_family_key"),
			resultSet.getString("receipt_number"),
			resultSet.getString("dart_corp_code"),
			resultSet.getString("name_ko"),
			resultSet.getString("name_en"),
			resultSet.getString("stock_code"),
			Market.valueOf(resultSet.getString("market")),
			DisclosureType.fromCode(resultSet.getString("disclosure_type")),
			resultSet.getString("title_ko"),
			resultSet.getString("title_en"),
			resultSet.getString("event_type"),
			enumValue(com.kmarket.navigator.backend.news.domain.NewsSentiment.class,
				resultSet.getString("sentiment")),
			enumValue(com.kmarket.navigator.backend.news.domain.NewsImportance.class,
				resultSet.getString("importance")),
			enumValue(com.kmarket.navigator.backend.news.domain.MarketImpact.class,
				resultSet.getString("market_impact")),
			resultSet.getString("submitter"),
			resultSet.getObject("filed_date", java.time.LocalDate.class),
			resultSet.getObject("detected_at", OffsetDateTime.class).toInstant(),
			resultSet.getString("remark"),
			resultSet.getBoolean("correction"),
			DocumentStatus.valueOf(resultSet.getString("document_status")),
			IndexStatus.valueOf(resultSet.getString("index_status")),
			resultSet.getString("official_url")
		);
	}

	private List<UUID> uuidArray(ResultSet resultSet, String column) throws SQLException {
		java.sql.Array sqlArray = resultSet.getArray(column);
		if (sqlArray == null) {
			return List.of();
		}
		Object[] values = (Object[]) sqlArray.getArray();
		return java.util.Arrays.stream(values)
			.map(value -> value instanceof UUID id ? id : UUID.fromString(value.toString()))
			.toList();
	}

	private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
		return value == null ? null : Enum.valueOf(type, value);
	}

	private static boolean isCorrection(OpenDartFiling filing) {
		return filing.reportName().contains("정정") || filing.remark().contains("정");
	}

	private static String filingFamilyKey(String title) {
		return title
			.replaceFirst("^\\s*(?:\\[(?:기재정정|첨부정정|정정)]|(?:기재정정|첨부정정|정정))\\s*", "")
			.trim();
	}

	private static String officialUrl(String receiptNumber) {
		return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNumber;
	}

	private static String abbreviate(String value, int maximumLength) {
		return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
	}

	private record DisclosureDetailRow(
		UUID id,
		UUID issuerId,
		String filingFamilyKey,
		String receiptNumber,
		String corpCode,
		String issuerNameKo,
		String issuerNameEn,
		String stockCode,
		Market market,
		DisclosureType type,
		String titleKo,
		String titleEn,
		String eventType,
		com.kmarket.navigator.backend.news.domain.NewsSentiment sentiment,
		com.kmarket.navigator.backend.news.domain.NewsImportance importance,
		com.kmarket.navigator.backend.news.domain.MarketImpact marketImpact,
		String submitter,
		java.time.LocalDate filedDate,
		Instant detectedAt,
		String remark,
		boolean correction,
		DocumentStatus documentStatus,
		IndexStatus indexStatus,
		String officialUrl
	) {
		private DisclosureDetail toDetail(
			List<DisclosureDocument> documents,
			List<DisclosureVersion> versions
		) {
			return new DisclosureDetail(
				receiptNumber,
				corpCode,
				issuerNameKo,
				issuerNameEn,
				stockCode,
				market,
				type,
				titleKo,
				titleEn,
				eventType,
				sentiment,
				importance,
				marketImpact,
				submitter,
				filedDate,
				detectedAt,
				remark,
				correction,
				documentStatus,
				indexStatus,
				officialUrl,
				documents,
				versions
			);
		}
	}

	private record DocumentRow(
		UUID id,
		String sourceFilename,
		int version,
		String contentHash,
		byte[] payload
	) {
	}

	private record SignalClaim(String receiptNumber, int attempts) {
	}

	private record SignalSource(String title, String nameKo, String nameEn) {
	}
}
