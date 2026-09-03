package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.github.luben.zstd.ZstdInputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@EnabledIfEnvironmentVariable(named = "DISCLOSURE_STORED_PAYLOAD_AUDIT", matches = ".+")
class DisclosureHtmlStoredPayloadAuditTests {
	@Test
	void checksStoredSectionsAndArchivesWithoutDatabaseWrites() throws Exception {
		var mapper = JsonMapper.builder().build();
		var parser = new OpenDartArchiveParser(mapper);
		var snapshot = mapper.readTree(Files.readString(Path.of(System.getenv("DISCLOSURE_STORED_PAYLOAD_AUDIT"))));
		Map<String, Integer> counts = new TreeMap<>();
		var details = new ArrayList<Map<String, String>>();
		var root = Path.of("data/opendart-archives").toRealPath();
		for (var entry : snapshot) {
			String outcome = "ARCHIVE_MISSING";
			String reason = "";
			ObjectNode payload;
			try (var input = new ZstdInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(entry.path("payload_zstd").asString())))) {
				byte[] decoded = input.readNBytes(128 * 1024 * 1024 + 1);
				assertThat(decoded.length).isLessThanOrEqualTo(128 * 1024 * 1024);
				payload = (ObjectNode) mapper.readTree(decoded);
			}
			for (var archive : entry.path("archives")) {
				if (!archive.path("kind").asString().equals("OPENDART_ZIP")) continue;
				var path = root.resolve(archive.path("path").asString()).normalize();
				if (!path.startsWith(root) || !Files.exists(path) || !path.toRealPath().startsWith(root)) continue;
				try {
					var documents = parser.parseDocuments(Files.readAllBytes(path));
					var matching = documents.stream().filter(doc -> doc.contentHash().equals(entry.path("content_hash").asString())).findFirst();
					if (matching.isEmpty()) { outcome = "HASH_MISMATCH"; continue; }
					try {
						var augmented = DisclosureHtmlBackfillRunner.augmentPayload(payload, matching.get(), mapper);
						assertThat(augmented.path("sections")).isEqualTo(payload.path("sections"));
						outcome = "RESTORABLE";
					} catch (IllegalStateException mismatch) {
						var source = matching.get();
						var sections = source.sections().stream().map(section -> new com.kmarket.navigator.backend.disclosure.domain.DisclosureSection(
							java.util.UUID.randomUUID(), section.ordinal(), section.kind(), section.heading(), section.text(), section.tableData())).toList();
						com.kmarket.navigator.backend.translation.application.DisclosureHtmlRenderer.render(
							new com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument(java.util.UUID.randomUUID(), source.filename(), 1,
								source.contentHash(), source.sanitizedHtml(), sections), Map.of());
						outcome = "VERSIONED_REPAIR";
					}
					break;
				} catch (OpenDartException error) { outcome = error.errorCode(); }
				catch (IllegalStateException error) { outcome = "STRUCTURE_REVIEW"; reason = error.getMessage(); }
			}
			counts.merge(outcome, 1, Integer::sum);
			details.add(Map.of("stock", entry.path("stock_code").asString(), "receipt", entry.path("receipt_number").asString(), "outcome", outcome, "reason", reason));
		}
		Files.writeString(Path.of("build/stored-html-audit.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(details));
		System.out.println("STORED_HTML_AUDIT " + counts);
		assertThat(snapshot.size()).isPositive();
	}
}
