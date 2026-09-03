package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.translation.application.DisclosureHtmlRenderer;

@Component
@Profile("html-backfill")
public class DisclosureHtmlBackfillRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DisclosureHtmlBackfillRunner.class);
	private static final int MAX_BYTES = 128 * 1024 * 1024;
	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final OpenDartArchiveParser parser;
	private final OpenDartProperties properties;
	private final boolean apply;
	private final int limit;
	private final ConfigurableApplicationContext applicationContext;
	private final com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway gateway;
	private final UUID afterId;
	private final UUID beforeId;
	private final java.time.LocalDate sinceDate;
	private final int fetchLimit;
	private int fetched;
	private final Path backupPath;
	private java.nio.channels.FileChannel backup;
	private final boolean reparse;
	private final com.kmarket.navigator.backend.disclosure.infrastructure.persistence.DisclosureDocumentRepairService repair;
	private final FileDocumentArchiveStore archiveStore;
	private final java.util.List<String> receipts;
	private final boolean forceFetch;

	@Bean
	static java.time.Clock backfillClock() { return java.time.Clock.systemUTC(); }

	public DisclosureHtmlBackfillRunner(JdbcClient jdbc, ObjectMapper mapper, OpenDartArchiveParser parser,
		OpenDartProperties properties, @Value("${kmarket.html-backfill.apply:false}") boolean apply,
		@Value("${kmarket.html-backfill.limit:100}") int limit, ConfigurableApplicationContext applicationContext,
		com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway gateway,
		@Value("${kmarket.html-backfill.after-id:00000000-0000-0000-0000-000000000000}") UUID afterId,
		@Value("${kmarket.html-backfill.before-id:ffffffff-ffff-ffff-ffff-ffffffffffff}") UUID beforeId,
		@Value("${kmarket.html-backfill.since-date:1900-01-01}") java.time.LocalDate sinceDate,
		@Value("${kmarket.html-backfill.fetch-limit:0}") int fetchLimit,
		@Value("${kmarket.html-backfill.backup:}") String backupPath,
		@Value("${kmarket.html-backfill.reparse:false}") boolean reparse,
		com.kmarket.navigator.backend.disclosure.infrastructure.persistence.DisclosureDocumentRepairService repair,
		FileDocumentArchiveStore archiveStore,
		@Value("${kmarket.html-backfill.receipts:}") String receipts,
		@Value("${kmarket.html-backfill.force-fetch:false}") boolean forceFetch) {
		this.jdbc = jdbc; this.mapper = mapper; this.parser = parser; this.properties = properties;
		this.apply = apply; this.limit = Math.clamp(limit, 1, 2_000); this.applicationContext = applicationContext;
		this.gateway = gateway; this.afterId = afterId; this.beforeId = beforeId; this.fetchLimit = Math.clamp(fetchLimit, 0, 20);
		this.sinceDate = sinceDate;
		this.backupPath = backupPath.isBlank() ? null : Path.of(backupPath);
		this.reparse = reparse; this.repair = repair; this.archiveStore = archiveStore;
		this.receipts = receipts.isBlank() ? java.util.List.of() : java.util.Arrays.stream(receipts.split(",")).map(String::trim).distinct().toList();
		this.forceFetch = forceFetch;
		if (this.receipts.size() > 20 || this.receipts.stream().anyMatch(receipt -> !receipt.matches("[0-9]{14}"))
			|| (forceFetch && (!reparse || this.receipts.isEmpty() || fetchLimit < this.receipts.size()))) {
			throw new IllegalArgumentException("Forced source repair requires up to 20 explicit receipts, reparse and sufficient fetch limit");
		}
	}

	@Override
	public void run(ApplicationArguments arguments) {
		try {
			if (apply) {
				if (backupPath == null || !backupPath.isAbsolute()) throw new IllegalArgumentException("Absolute backup path required");
				backup = java.nio.channels.FileChannel.open(backupPath,
					java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
					java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
			}
			execute();
		} catch (java.io.IOException error) { throw new IllegalStateException("Backup could not be written", error); }
		finally {
			if (backup != null) try { backup.close(); } catch (java.io.IOException ignored) { }
			applicationContext.close();
		}
	}

	private void execute() {
		var candidates = jdbc.sql("""
			SELECT document.id, document.disclosure_id, document.content_hash, disclosure.receipt_number,
			    document.parser_version, document.original_bytes, document.compressed_bytes, document.source_filename
			FROM disclosure_document document JOIN disclosure ON disclosure.id = document.disclosure_id
			WHERE document.is_current
			    AND (:forceFetch OR document.parser_version NOT IN ('opendart-html-v4-restored-v5', 'opendart-html-v5', 'opendart-html-v6'))
			    AND (:allReceipts OR disclosure.receipt_number IN (:receipts))
			    AND document.id > :afterId
			    AND document.id < :beforeId
			    AND disclosure.filed_date >= :sinceDate
			ORDER BY document.id LIMIT :limit
			""").param("forceFetch", forceFetch).param("allReceipts", receipts.isEmpty())
			.param("receipts", receipts.isEmpty() ? java.util.List.of("00000000000000") : receipts)
			.param("limit", limit).param("afterId", afterId).param("beforeId", beforeId).param("sinceDate", sinceDate).query((rs, row) -> new Candidate(rs.getObject("id", UUID.class),
				rs.getObject("disclosure_id", UUID.class), rs.getString("content_hash"), rs.getString("receipt_number"),
				rs.getString("parser_version"), rs.getLong("original_bytes"), rs.getLong("compressed_bytes"),
				rs.getString("source_filename"))).list();
		int restored = 0;
		for (var candidate : candidates) {
			try { if (restore(candidate)) restored++; }
			catch (Exception exception) {
				log.warn("HTML 백필 실패 document={} type={} code={}", candidate.id(), exception.getClass().getSimpleName(),
					exception instanceof OpenDartException dart ? dart.errorCode() : "STRUCTURE_OR_STORAGE_ERROR");
			}
			if (restored > 0 && restored % 1000 == 0) log.info("HTML 백필 진행 restored={}", restored);
		}
		log.info("HTML 백필 결과 apply={} scanned={} restorable={} unresolved={} downloaded={} nextAfterId={}",
			apply, candidates.size(), restored, candidates.size() - restored, fetched,
			candidates.isEmpty() ? afterId : candidates.getLast().id());
	}

	private boolean restore(Candidate candidate) throws Exception {
		byte[] previous = jdbc.sql("SELECT payload_zstd FROM disclosure_document WHERE id = :id")
			.param("id", candidate.id()).query(byte[].class).optional().orElse(null);
		if (forceFetch) {
			if (fetched >= fetchLimit) return false;
			fetched++;
			return persistFetched(candidate, previous, null, gateway.fetchDocuments(candidate.receipt()));
		}
		if (previous == null) {
			if (!reparse || fetched >= fetchLimit) return false;
			fetched++;
			return persistFetched(candidate, null, null, gateway.fetchDocuments(candidate.receipt()));
		}
		ObjectNode payload;
		try (var input = new ZstdInputStream(new ByteArrayInputStream(previous))) {
			byte[] decoded = input.readNBytes(MAX_BYTES + 1);
			if (decoded.length > MAX_BYTES) throw new IllegalStateException("Payload size limit");
			payload = (ObjectNode) mapper.readTree(decoded);
		}
		var archives = jdbc.sql("""
			SELECT relative_path, sha256, archive_kind FROM disclosure_archive
			WHERE disclosure_id = :id AND archive_status = 'VERIFIED'
			ORDER BY archive_kind
			""").param("id", candidate.disclosureId()).query((rs, row) -> new Archive(
				rs.getString("relative_path"), rs.getString("sha256"), rs.getString("archive_kind"))).list();
		Path root = properties.archiveRoot().toRealPath();
		for (var archive : archives) {
			try {
			Path path = root.resolve(archive.path()).normalize();
			if (!path.startsWith(root) || !Files.exists(path) || !path.toRealPath().startsWith(root)) continue;
			if (Files.size(path) > MAX_BYTES) continue;
			byte[] bytes = Files.readAllBytes(path);
			if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(archive.hash())) continue;
			List<OpenDartDocument> documents;
			if ("DART_VIEWER_HTML".equals(archive.kind())) {
				try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
					if (zip.getNextEntry() == null) continue;
					byte[] html = zip.readNBytes(MAX_BYTES + 1);
					if (html.length > MAX_BYTES) continue;
					documents = List.of(parser.parseViewerDocument(candidate.receipt(), html));
				}
			} else documents = parser.parseDocuments(bytes);
			var matching = documents.stream().filter(doc -> doc.contentHash().equals(candidate.hash())).findFirst();
			if (matching.isEmpty() || matching.get().sanitizedHtml().isBlank()) continue;
			return persist(candidate, previous, payload, matching.get());
			} catch (OpenDartException | IllegalStateException error) {
				log.warn("HTML 아카이브 재검증 필요 document={} kind={} type={}", candidate.id(), archive.kind(), error.getClass().getSimpleName());
			}
		}
		if (fetched < fetchLimit) {
			fetched++;
			return persistFetched(candidate, previous, payload, gateway.fetchDocuments(candidate.receipt()));
		}
		return false;
	}

	private boolean persistFetched(Candidate candidate, byte[] previous, ObjectNode payload,
		com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocumentFetch fetch) throws Exception {
		var same = fetch.documents().stream().filter(doc -> doc.contentHash().equals(candidate.hash())
			&& doc.filename().equals(candidate.filename())).findFirst();
		if (forceFetch && fetch.documents().stream().anyMatch(doc -> doc.contentHash().equals(candidate.hash())
			&& (doc.filename().equals(candidate.filename()) || doc.filename().equals(candidate.receipt() + ".viewer.html")))) return true;
		if (same.isPresent() && payload != null) return persist(candidate, previous, payload, same.get());
		if (!reparse) return false;
		// 같은 접수번호의 검증된 XML·뷰어 메인 문서만 서로 대체한다. 첨부 문서 이름은 추정하지 않는다.
		var candidates = fetch.documents().stream().filter(doc -> doc.filename().equals(candidate.filename())
			|| (candidate.filename().equals(candidate.receipt() + ".xml")
				&& doc.filename().equals(candidate.receipt() + ".viewer.html")
				&& fetch.sources().stream().anyMatch(source -> source.kind() == com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind.DART_VIEWER_HTML
					&& source.status() == com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus.VERIFIED))
			|| (candidate.filename().equals(candidate.receipt() + ".viewer.html")
				&& doc.filename().equals(candidate.receipt() + ".xml")
				&& fetch.sources().stream().anyMatch(source -> source.kind() == com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind.OPENDART_ZIP
					&& source.status() == com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus.VERIFIED))).toList();
		if (candidates.size() != 1) return false;
		var original = candidates.getFirst();
		var sections = original.sections().stream().map(section -> new DisclosureSection(UUID.randomUUID(),
			section.ordinal(), section.kind(), section.heading(), section.text(), section.tableData())).toList();
		if (original.sanitizedHtml().isBlank() || sections.isEmpty()) return false;
		DisclosureHtmlRenderer.render(new DisclosureDocument(candidate.id(), original.filename(), 1,
			original.contentHash(), original.sanitizedHtml(), sections), java.util.Map.of());
		if (!apply) return true;
		writeBackup(candidate, previous);
		var archives = archiveStore.storeRecovered(candidate.receipt(), fetch);
		return repair.restoreVersion(candidate.id(), candidate.disclosureId(), candidate.receipt(), previous, original, archives);
	}

	private boolean persist(Candidate candidate, byte[] previous, ObjectNode payload, OpenDartDocument original) throws Exception {
			// 원문 해시와 기존 섹션 ID를 유지해 RAG 인용 및 번역 캐시를 무효화하지 않는다.
			ObjectNode augmented;
			try { augmented = augmentPayload(payload, original, mapper); }
			catch (IllegalStateException mismatch) {
				if (!reparse) throw mismatch;
				var sections = original.sections().stream().map(section -> new DisclosureSection(UUID.randomUUID(),
					section.ordinal(), section.kind(), section.heading(), section.text(), section.tableData())).toList();
				DisclosureHtmlRenderer.render(new DisclosureDocument(candidate.id(), original.filename(), 1,
					original.contentHash(), original.sanitizedHtml(), sections), java.util.Map.of());
				if (!apply) return true;
				writeBackup(candidate, previous);
				return repair.restoreVersion(candidate.id(), candidate.disclosureId(), candidate.receipt(), previous, original);
			}
			byte[] decoded = mapper.writeValueAsBytes(augmented);
			byte[] compressed = Zstd.compress(decoded, 6);
			if (apply) {
				writeBackup(candidate, previous);
				return jdbc.sql("""
				UPDATE disclosure_document SET payload_zstd = :payload, original_bytes = :original,
				    compressed_bytes = :compressed, parser_version = 'opendart-html-v4-restored-v5'
				WHERE id = :id AND content_hash = :hash AND is_current AND payload_zstd = :previous
				""").param("payload", compressed).param("original", decoded.length)
				.param("compressed", compressed.length).param("id", candidate.id()).param("hash", candidate.hash())
				.param("previous", previous).update() == 1;
			}
			return true;
	}

	static ObjectNode augmentPayload(ObjectNode existing, OpenDartDocument original, ObjectMapper mapper) {
		var stored = new java.util.ArrayList<DisclosureSection>();
		for (var node : existing.path("sections")) stored.add(mapper.treeToValue(node, DisclosureSection.class));
		// 구버전 분할 방식은 유지하되 HTML의 모든 가시 내용과 기존 섹션이 정확히 대응해야 한다.
		DisclosureHtmlRenderer.render(new DisclosureDocument(UUID.randomUUID(), original.filename(), 1,
			original.contentHash(), original.sanitizedHtml(), stored), java.util.Map.of());
		ObjectNode result = existing.deepCopy();
		result.put("sanitizedHtml", original.sanitizedHtml());
		return result;
	}

	private void writeBackup(Candidate candidate, byte[] previous) throws java.io.IOException {
		var entry = mapper.createObjectNode();
		entry.put("id", candidate.id().toString()); entry.put("contentHash", candidate.hash());
		entry.put("parserVersion", candidate.parserVersion()); entry.put("originalBytes", candidate.originalBytes());
		entry.put("compressedBytes", candidate.compressedBytes());
		entry.set("archives", mapper.valueToTree(jdbc.sql("""
			SELECT archive_kind, archive_status, relative_path, sha256, size_bytes, error_code
			FROM disclosure_archive WHERE disclosure_id = :id
			""").param("id", candidate.disclosureId()).query().listOfRows()));
		if (previous == null) entry.putNull("payloadZstd");
		else entry.put("payloadZstd", java.util.Base64.getEncoder().encodeToString(previous));
		var buffer = java.nio.ByteBuffer.wrap((mapper.writeValueAsString(entry) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		while (buffer.hasRemaining()) backup.write(buffer);
		backup.force(true);
	}

	private record Candidate(UUID id, UUID disclosureId, String hash, String receipt, String parserVersion,
		long originalBytes, long compressedBytes, String filename) { }
	private record Archive(String path, String hash, String kind) { }
}
