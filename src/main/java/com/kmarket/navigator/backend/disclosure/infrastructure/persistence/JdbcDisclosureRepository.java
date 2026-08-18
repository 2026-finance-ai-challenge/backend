package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.kmarket.navigator.backend.disclosure.domain.Market;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

@Repository
class JdbcDisclosureRepository implements DisclosureRepository {

	private static final String DOCUMENT_JOB = "DISCLOSURE_DOCUMENT";
	private static final String EMBEDDING_JOB = "DISCLOSURE_EMBEDDING";

	private final JdbcClient jdbcClient;

	JdbcDisclosureRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
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
	public boolean saveFiling(OpenDartFiling filing) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
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

		jdbcClient.sql("""
			INSERT INTO ingestion_job (
			    id, job_type, business_key, status, attempts, available_at, created_at, updated_at
			)
			VALUES (:id, :jobType, :businessKey, 'PENDING', 0, :now, :now, :now)
			ON CONFLICT (job_type, business_key) DO NOTHING
			""")
			.param("id", UUID.randomUUID())
			.param("jobType", DOCUMENT_JOB)
			.param("businessKey", filing.receiptNumber())
			.param("now", now)
			.update();
		return true;
	}

	@Override
	public Optional<DocumentJob> claimDocumentJob(String workerId) {
		return jdbcClient.sql("""
			WITH candidate AS (
			    SELECT id
			    FROM ingestion_job
			    WHERE job_type = :jobType
			      AND available_at <= CURRENT_TIMESTAMP
			      AND (
			          status = 'PENDING'
			          OR (status = 'PROCESSING' AND locked_at < CURRENT_TIMESTAMP - INTERVAL '15 minutes')
			      )
			    ORDER BY available_at, created_at
			    FOR UPDATE SKIP LOCKED
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
			.param("workerId", workerId)
			.query((resultSet, rowNumber) -> new DocumentJob(
				resultSet.getString("business_key"),
				resultSet.getInt("attempts")
			))
			.optional();
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
			UUID documentId = jdbcClient.sql("""
				INSERT INTO disclosure_document (
				    id, disclosure_id, source_filename, version_no, is_current,
				    content_hash, body_text, created_at
				)
				SELECT :id, :disclosureId, :sourceFilename,
				       COALESCE(MAX(version_no), 0) + 1, TRUE,
				       :contentHash, :bodyText, :createdAt
				FROM disclosure_document
				WHERE disclosure_id = :disclosureId AND source_filename = :sourceFilename
				ON CONFLICT (disclosure_id, source_filename, content_hash)
				DO UPDATE SET body_text = EXCLUDED.body_text, is_current = TRUE
				RETURNING id
				""")
				.param("id", UUID.randomUUID())
				.param("disclosureId", disclosureId)
				.param("sourceFilename", document.filename())
				.param("contentHash", document.contentHash())
				.param("bodyText", document.bodyText())
				.param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
				.query(UUID.class)
				.single();

			jdbcClient.sql("DELETE FROM disclosure_section WHERE document_id = :documentId")
				.param("documentId", documentId)
				.update();
			for (var section : document.sections()) {
				jdbcClient.sql("""
					INSERT INTO disclosure_section (
					    id, document_id, ordinal, section_kind, heading, text_content, table_data
					)
					VALUES (
					    :id, :documentId, :ordinal, :sectionKind, :heading, :textContent,
					    CAST(:tableData AS JSONB)
					)
					""")
					.param("id", UUID.randomUUID())
					.param("documentId", documentId)
					.param("ordinal", section.ordinal())
					.param("sectionKind", section.kind().name())
					.param("heading", section.heading())
					.param("textContent", section.text())
					.param("tableData", section.tableData())
					.update();
			}
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
			    last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
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
			.param("jobType", EMBEDDING_JOB)
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
			LEFT JOIN security s ON s.id = d.security_id
			WHERE 1 = 1
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
			LEFT JOIN security s ON s.id = d.security_id
			WHERE d.receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.query(this::mapDetailRow)
			.optional();
		return detail.map(this::withDocuments);
	}

	@Override
	public Optional<IndexStatus> findIndexStatus(String receiptNumber) {
		return jdbcClient.sql("""
			SELECT index_status
			FROM disclosure
			WHERE receipt_number = :receiptNumber
			""")
			.param("receiptNumber", receiptNumber)
			.query(String.class)
			.optional()
			.map(IndexStatus::valueOf);
	}

	private DisclosureDetail withDocuments(DisclosureDetailRow row) {
		List<DocumentRow> rows = jdbcClient.sql("""
			SELECT id, source_filename, version_no, content_hash
			FROM disclosure_document
			WHERE disclosure_id = :disclosureId AND is_current = TRUE
			ORDER BY created_at, source_filename
			""")
			.param("disclosureId", row.id())
			.query((resultSet, rowNumber) -> new DocumentRow(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("source_filename"),
				resultSet.getInt("version_no"),
				resultSet.getString("content_hash")
			))
			.list();
		List<DisclosureDocument> documents = rows.stream()
			.map(document -> new DisclosureDocument(
				document.id(),
				document.sourceFilename(),
				document.version(),
				document.contentHash(),
				findSections(document.id())
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

	private record DocumentRow(UUID id, String sourceFilename, int version, String contentHash) {
	}
}
