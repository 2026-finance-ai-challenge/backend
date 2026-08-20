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
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartCorporation;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.domain.DocumentStatus;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

@Repository
class JdbcDisclosureRepository implements DisclosureRepository {

	private static final String DOCUMENT_JOB = "DISCLOSURE_DOCUMENT";
	private static final String EMBEDDING_JOB = "DISCLOSURE_EMBEDDING";
	private static final String METADATA_EMBEDDING_JOB = "DISCLOSURE_METADATA_EMBEDDING";
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
			int updated = jdbcClient.sql("""
				UPDATE security
				SET market = :market, common_stock = TRUE, active = TRUE,
				    master_updated_at = :now, updated_at = :now
				WHERE stock_code = :stockCode
				""")
				.param("market", stock.market().name())
				.param("now", now)
				.param("stockCode", stock.stockCode())
				.update();
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

		Optional<UUID> inserted = jdbcClient.sql("""
			INSERT INTO disclosure (
			    id, receipt_number, issuer_id, security_id, disclosure_type, title_ko,
			    submitter, filed_date, detected_at, official_url, remark, correction,
			    document_status, created_at, updated_at
			)
			VALUES (
			    :id, :receiptNumber, :issuerId, :securityId, :disclosureType, :titleKo,
			    :submitter, :filedDate, :detectedAt, :officialUrl, :remark, :correction,
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
			.param("submitter", filing.submitter())
			.param("filedDate", filing.filedDate())
			.param("detectedAt", now)
			.param("officialUrl", officialUrl(filing.receiptNumber()))
			.param("remark", filing.remark())
			.param("correction", isCorrection(filing))
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
				    submitter = :submitter,
				    filed_date = :filedDate,
				    remark = :remark,
				    correction = :correction,
				    updated_at = :updatedAt
				WHERE receipt_number = :receiptNumber
				""")
				.param("issuerId", issuerId)
				.param("securityId", securityId)
				.param("disclosureType", filing.disclosureType().code())
				.param("titleKo", filing.reportName())
				.param("submitter", filing.submitter())
				.param("filedDate", filing.filedDate())
				.param("remark", filing.remark())
				.param("correction", isCorrection(filing))
				.param("updatedAt", now)
				.param("receiptNumber", filing.receiptNumber())
				.update();
			return false;
		}

		if (filing.stockCode() != null) {
			jdbcClient.sql("""
				INSERT INTO ingestion_job (
				    id, job_type, business_key, status, attempts,
				    available_at, created_at, updated_at
				)
				SELECT :id, :jobType, :businessKey, 'PENDING', 0, :now, :now, :now
				FROM service_stock_universe universe
				WHERE universe.stock_code = :stockCode
				ON CONFLICT (job_type, business_key) DO NOTHING
				""")
				.param("id", UUID.randomUUID())
				.param("jobType", DOCUMENT_JOB)
				.param("businessKey", filing.receiptNumber())
				.param("now", now)
				.param("stockCode", filing.stockCode())
				.update();
			jdbcClient.sql("""
				INSERT INTO ingestion_job (
				    id, job_type, business_key, status, attempts,
				    available_at, created_at, updated_at
				)
				SELECT :id, :jobType, :businessKey, 'PENDING', 0, :now, :now, :now
				FROM service_stock_universe universe
				WHERE universe.stock_code = :stockCode
				ON CONFLICT (job_type, business_key) DO NOTHING
				""")
				.param("id", UUID.randomUUID())
				.param("jobType", METADATA_EMBEDDING_JOB)
				.param("businessKey", filing.receiptNumber())
				.param("now", now)
				.param("stockCode", filing.stockCode())
				.update();
		}
		return true;
	}

	@Override
	public Optional<DocumentJob> claimDocumentJob(String workerId) {
		return jdbcClient.sql("""
			WITH candidate AS (
			    SELECT job.id
			    FROM ingestion_job job
			    JOIN disclosure disclosure ON disclosure.receipt_number = job.business_key
			    JOIN security security ON security.id = disclosure.security_id
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
			    ORDER BY job.attempts DESC, job.available_at, job.created_at
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
			RETURNING job.business_key, job.attempts
			""")
			.param("jobType", DOCUMENT_JOB)
			.param("provider", OPEN_DART_DOCUMENT_PROVIDER)
			.param("workerId", workerId)
			.query((resultSet, rowNumber) -> new DocumentJob(
				resultSet.getString("business_key"),
				resultSet.getInt("attempts")
			))
			.optional();
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
	public void completeDocumentJob(String receiptNumber, List<OpenDartDocument> documents) {
		if (documents.isEmpty()) {
			throw new IllegalArgumentException("Disclosure documents must not be empty");
		}
		UUID disclosureId = disclosureId(receiptNumber);
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
				    compressed_bytes, created_at
				)
				SELECT :id, :disclosureId, :sourceFilename,
				       COALESCE(MAX(version_no), 0) + 1, TRUE,
				       :contentHash, NULL, :payloadZstd, :originalBytes,
				       :compressedBytes, :createdAt
				FROM disclosure_document
				WHERE disclosure_id = :disclosureId AND source_filename = :sourceFilename
				ON CONFLICT (disclosure_id, source_filename, content_hash)
				DO UPDATE SET body_text = NULL,
				              payload_zstd = EXCLUDED.payload_zstd,
				              original_bytes = EXCLUDED.original_bytes,
				              compressed_bytes = EXCLUDED.compressed_bytes,
				              is_current = TRUE
				RETURNING id
				""")
				.param("id", UUID.randomUUID())
				.param("disclosureId", disclosureId)
				.param("sourceFilename", document.filename())
				.param("contentHash", document.contentHash())
				.param("payloadZstd", payload.compressed())
				.param("originalBytes", payload.originalBytes())
				.param("compressedBytes", payload.compressedBytes())
				.param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
				.query(UUID.class)
				.single();

			jdbcClient.sql("DELETE FROM disclosure_section WHERE document_id = :documentId")
				.param("documentId", documentId)
				.update();
		}

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
	}

	@Override
	@Transactional
	public void retryDocumentJob(String receiptNumber, String errorCode, Duration delay) {
		jdbcClient.sql("""
			UPDATE ingestion_job
			SET status = 'PENDING',
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
	public List<DisclosureSummary> findAll(DisclosureListQuery query, int fetchSize) {
		StringBuilder sql = new StringBuilder("""
			SELECT d.receipt_number, i.dart_corp_code, i.name_ko, i.name_en,
			       s.stock_code, COALESCE(s.market, 'UNKNOWN') AS market,
			       d.disclosure_type, d.title_ko, NULL AS title_en, d.filed_date,
			       d.detected_at, d.correction, d.document_status, d.index_status, d.official_url
			FROM disclosure d
			JOIN issuer i ON i.id = d.issuer_id
			JOIN security s ON s.id = d.security_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			WHERE s.active AND s.common_stock
			""");
		Map<String, Object> parameters = new LinkedHashMap<>();
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
			SELECT d.id, d.receipt_number, i.dart_corp_code, i.name_ko, i.name_en,
			       s.stock_code, COALESCE(s.market, 'UNKNOWN') AS market,
			       d.disclosure_type, d.title_ko, NULL AS title_en, d.submitter,
			       d.filed_date, d.detected_at, d.remark, d.correction,
			       d.document_status, d.index_status, d.official_url
			FROM disclosure d
			JOIN issuer i ON i.id = d.issuer_id
			JOIN security s ON s.id = d.security_id
			JOIN service_stock_universe universe ON universe.stock_code = s.stock_code
			WHERE d.receipt_number = :receiptNumber
			  AND s.active AND s.common_stock
			""")
			.param("receiptNumber", receiptNumber)
			.query(this::mapDetailRow)
			.optional();
		return detail.map(this::withDocuments);
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
			    id, job_type, business_key, status, attempts,
			    available_at, last_error_code, created_at, updated_at
			)
			VALUES (
			    :id, :jobType, :businessKey, 'PENDING', 0,
			    CURRENT_TIMESTAMP, 'ON_DEMAND', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			)
			ON CONFLICT (job_type, business_key) DO UPDATE
			SET status = 'PENDING', attempts = 0, available_at = CURRENT_TIMESTAMP,
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
		List<DisclosureDocument> documents = rows.stream()
			.map(document -> new DisclosureDocument(
				document.id(),
				document.sourceFilename(),
				document.version(),
				document.contentHash(),
				document.payload() == null
					? findSections(document.id())
					: payloadCodec.decode(document.payload())
			))
			.toList();
		return row.toDetail(documents);
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

	private void appendFilters(
		StringBuilder sql,
		Map<String, Object> parameters,
		DisclosureListQuery query
	) {
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
			resultSet.getObject("filed_date", java.time.LocalDate.class),
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
			resultSet.getString("receipt_number"),
			resultSet.getString("dart_corp_code"),
			resultSet.getString("name_ko"),
			resultSet.getString("name_en"),
			resultSet.getString("stock_code"),
			Market.valueOf(resultSet.getString("market")),
			DisclosureType.fromCode(resultSet.getString("disclosure_type")),
			resultSet.getString("title_ko"),
			resultSet.getString("title_en"),
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

	private static boolean isCorrection(OpenDartFiling filing) {
		return filing.reportName().contains("정정") || filing.remark().contains("정");
	}

	private static String officialUrl(String receiptNumber) {
		return "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + receiptNumber;
	}

	private static String abbreviate(String value, int maximumLength) {
		return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
	}

	private record DisclosureDetailRow(
		UUID id,
		String receiptNumber,
		String corpCode,
		String issuerNameKo,
		String issuerNameEn,
		String stockCode,
		Market market,
		DisclosureType type,
		String titleKo,
		String titleEn,
		String submitter,
		java.time.LocalDate filedDate,
		Instant detectedAt,
		String remark,
		boolean correction,
		DocumentStatus documentStatus,
		IndexStatus indexStatus,
		String officialUrl
	) {
		private DisclosureDetail toDetail(List<DisclosureDocument> documents) {
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
				submitter,
				filedDate,
				detectedAt,
				remark,
				correction,
				documentStatus,
				indexStatus,
				officialUrl,
				documents
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
}
