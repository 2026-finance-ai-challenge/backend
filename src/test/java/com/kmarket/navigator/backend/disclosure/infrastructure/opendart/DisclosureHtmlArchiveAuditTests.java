package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.translation.application.DisclosureHtmlRenderer;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "DISCLOSURE_HTML_ARCHIVE_AUDIT", matches = "1")
class DisclosureHtmlArchiveAuditTests {
	@Test
	void mapsExistingArchivesToOriginalHtmlWithoutChangingSections() throws Exception {
		var parser = new OpenDartArchiveParser(JsonMapper.builder().build());
		var failures = new ArrayList<String>();
		int checked = 0;
		var invalidSources = new ArrayList<String>();
		try (var paths = Files.walk(Path.of("data/opendart-archives"))) {
			var archives = paths.filter(path -> path.toString().endsWith(".api.zip"))
				.sorted().limit(40).toList();
			for (var archive : archives) {
				java.util.List<com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument> documents;
				try { documents = parser.parseDocuments(Files.readAllBytes(archive)); }
				catch (OpenDartException error) { invalidSources.add(archive.getFileName() + ":" + error.errorCode()); continue; }
				for (var parsed : documents) {
					var source = new DisclosureDocument(UUID.randomUUID(), parsed.filename(), 1,
						parsed.contentHash(), parsed.sanitizedHtml(), parsed.sections().stream().map(section ->
							new DisclosureSection(UUID.randomUUID(), section.ordinal(), section.kind(), section.heading(), section.text(), section.tableData())).toList());
					try {
						var html = DisclosureHtmlRenderer.render(source, Map.of());
						assertThat(html).doesNotContain("<script");
						checked++;
					} catch (RuntimeException error) {
						if (failures.isEmpty()) {
							Files.writeString(Path.of("build/html-audit-source.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(source));
						}
						failures.add(archive.getFileName() + ":" + error.getMessage());
					}
				}
			}
		}
		System.out.printf("HTML_ARCHIVE_AUDIT checked=%d failed=%d examples=%s%n", checked, failures.size(), failures.stream().limit(5).toList());
		System.out.printf("HTML_ARCHIVE_INVALID_SOURCE count=%d files=%s%n", invalidSources.size(), invalidSources);
		assertThat(checked).isPositive();
		assertThat(failures).isEmpty();
	}
}
