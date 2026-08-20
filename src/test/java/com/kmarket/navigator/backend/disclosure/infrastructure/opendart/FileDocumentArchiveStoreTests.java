package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentJob;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocumentFetch;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSource;

class FileDocumentArchiveStoreTests {

	@Test
	void storesArchivesUnderStockFolderWithAtomicReplacementNames() throws Exception {
		Path root = Files.createTempDirectory("kmarket-archives-");
		OpenDartProperties properties = new OpenDartProperties();
		properties.setArchiveRoot(root);
		FileDocumentArchiveStore store = new FileDocumentArchiveStore(properties);
		DocumentJob job = new DocumentJob("20260818000021", "005930", "삼성전자", 1);

		var stored = store.store(job, new OpenDartDocumentFetch(
			List.of(),
			List.of(new OpenDartSource(
				DocumentArchiveKind.OPENDART_ZIP,
				DocumentArchiveStatus.VERIFIED,
				new byte[] {1, 2, 3},
				null
			))
		));

		assertThat(stored).singleElement().satisfies(archive -> {
			assertThat(archive.relativePath()).isEqualTo("005930_삼성전자/20260818000021.api.zip");
			assertThat(archive.sizeBytes()).isEqualTo(3);
		});
		assertThat(Files.readAllBytes(root.resolve(stored.getFirst().relativePath())))
			.containsExactly(1, 2, 3);
	}

	@Test
	void wrapsViewerHtmlInZipSoEveryPersistedSourceIsAnArchive() throws Exception {
		Path root = Files.createTempDirectory("kmarket-archives-");
		OpenDartProperties properties = new OpenDartProperties();
		properties.setArchiveRoot(root);
		FileDocumentArchiveStore store = new FileDocumentArchiveStore(properties);
		DocumentJob job = new DocumentJob("20260818000021", "005930", "삼성전자", 1);

		var stored = store.store(job, new OpenDartDocumentFetch(
			List.of(),
			List.of(new OpenDartSource(
				DocumentArchiveKind.DART_VIEWER_HTML,
				DocumentArchiveStatus.VERIFIED,
				"<html>공시</html>".getBytes(),
				null
			))
		));

		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(
			root.resolve(stored.getFirst().relativePath())
		))) {
			assertThat(zip.getNextEntry().getName()).isEqualTo("20260818000021.viewer.html");
		}
	}
}
