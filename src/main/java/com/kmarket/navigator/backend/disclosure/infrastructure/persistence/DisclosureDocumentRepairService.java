package com.kmarket.navigator.backend.disclosure.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.translation.application.DisclosureHtmlRenderer;

@Service
public class DisclosureDocumentRepairService {

	private final JdbcClient jdbc;
	private final DisclosurePayloadCodec codec;
	private final com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository repository;

	public DisclosureDocumentRepairService(JdbcClient jdbc, DisclosurePayloadCodec codec,
		com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository repository) {
		this.jdbc = jdbc; this.codec = codec; this.repository = repository;
	}

	@Transactional
	public boolean restoreVersion(UUID id, UUID disclosureId, String receipt, byte[] previous, OpenDartDocument original) {
		return restoreVersion(id, disclosureId, receipt, previous, original, java.util.List.of());
	}

	@Transactional
	public boolean restoreVersion(UUID id, UUID disclosureId, String receipt, byte[] previous, OpenDartDocument original,
		java.util.List<com.kmarket.navigator.backend.disclosure.application.port.StoredDocumentArchive> archives) {
		jdbc.sql("SELECT id FROM disclosure WHERE id = :id AND receipt_number = :receipt FOR UPDATE")
			.param("id", disclosureId).param("receipt", receipt).query(UUID.class).single();
		// 진행 중인 수집·색인 작업은 건너뛰며, 백업한 현재 원문만 교체한다.
		boolean busy = jdbc.sql("""
			SELECT EXISTS (SELECT 1 FROM ingestion_job WHERE business_key = :receipt AND status = 'PROCESSING'
			    AND job_type IN ('DISCLOSURE_DOCUMENT', 'DISCLOSURE_EMBEDDING', 'DISCLOSURE_SIGNAL'))
			""").param("receipt", receipt).query(Boolean.class).single();
		if (busy) return false;
		var current = jdbc.sql("""
			SELECT source_filename FROM disclosure_document
			WHERE id = :id AND disclosure_id = :disclosureId AND is_current
			    AND payload_zstd IS NOT DISTINCT FROM :previous FOR UPDATE
			""").param("id", id).param("disclosureId", disclosureId)
			.param("previous", previous, java.sql.Types.BINARY).query(String.class).optional();
		if (current.isEmpty()) return false;
		if (original.sanitizedHtml().isBlank() || original.sections().isEmpty()
			|| !(current.get().equals(original.filename()) || (current.get().equals(receipt + ".xml")
				&& original.filename().equals(receipt + ".viewer.html")))) {
			throw new IllegalArgumentException("Replacement document identity or content mismatch");
		}
		var payload = codec.encode(original);
		var decoded = codec.decodePayload(payload.compressed());
		DisclosureHtmlRenderer.render(new DisclosureDocument(id, original.filename(), 1, original.contentHash(),
			decoded.sanitizedHtml(), decoded.sections()), java.util.Map.of());
		jdbc.sql("UPDATE disclosure_document SET is_current = FALSE WHERE id = :id").param("id", id).update();
		jdbc.sql("""
			INSERT INTO disclosure_document (id, disclosure_id, source_filename, version_no, is_current,
			    content_hash, payload_zstd, original_bytes, compressed_bytes, parser_version, created_at)
			SELECT :id, :disclosureId, :filename, COALESCE(MAX(version_no), 0) + 1, TRUE,
			    :hash, :payload, :originalBytes, :compressedBytes, 'opendart-html-v6', CURRENT_TIMESTAMP
			FROM disclosure_document WHERE disclosure_id = :disclosureId AND source_filename = :filename
			""").param("id", UUID.randomUUID()).param("disclosureId", disclosureId).param("filename", current.get())
			.param("hash", original.contentHash()).param("payload", payload.compressed())
			.param("originalBytes", payload.originalBytes()).param("compressedBytes", payload.compressedBytes()).update();
		jdbc.sql("UPDATE disclosure SET index_status = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
			.param("id", disclosureId).update();
		for (String kind : java.util.List.of("DISCLOSURE_EMBEDDING", "DISCLOSURE_SIGNAL")) {
			jdbc.sql("""
				INSERT INTO ingestion_job (id, job_type, business_key, status, attempts, available_at, created_at, updated_at)
				VALUES (:id, :kind, :receipt, 'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				ON CONFLICT (job_type, business_key) DO UPDATE SET status = 'PENDING', attempts = 0,
				    available_at = CURRENT_TIMESTAMP, locked_at = NULL, locked_by = NULL,
				    last_error_code = 'PARSER_V6_REPAIR', updated_at = CURRENT_TIMESTAMP
				WHERE EXCLUDED.job_type <> 'DISCLOSURE_SIGNAL' OR ingestion_job.status <> 'COMPLETED'
				""").param("id", UUID.randomUUID()).param("kind", kind).param("receipt", receipt).update();
		}
		if (!archives.isEmpty()) repository.recordDocumentArchives(receipt, archives);
		return true;
	}
}
