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
	void preservesRawViewerProvenanceWithoutAliasingInputBytes() throws Exception {
		Path root = Files.createTempDirectory("kmarket-viewer-provenance-");
		var properties = new OpenDartProperties(); properties.setArchiveRoot(root);
		byte[] raw = "<p>원래 응답</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		var source = new OpenDartSource(DocumentArchiveKind.DART_VIEWER_HTML, DocumentArchiveStatus.VERIFIED,
			"<html>결합된 본문</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, java.util.Map.of("page-1.raw", raw));
		byte[] expected = raw.clone(); raw[0] = 0; source.provenance().get("page-1.raw")[0] = 1;
		var stored = new FileDocumentArchiveStore(properties).storeRecovered("20240416000014", new OpenDartDocumentFetch(List.of(), List.of(source)));
		try (var zip = new ZipInputStream(Files.newInputStream(root.resolve(stored.getFirst().relativePath())))) {
			assertThat(zip.getNextEntry().getName()).isEqualTo("20240416000014.viewer.html");
			assertThat(zip.getNextEntry().getName()).isEqualTo("page-1.raw");
			assertThat(zip.readAllBytes()).isEqualTo(expected);
		}
	}

	@Test
	void keepsRecoveredSourcesImmutableAndChecksExistingBytes() throws Exception {
		Path root = Files.createTempDirectory("kmarket-recovered-archives-");
		var properties = new OpenDartProperties(); properties.setArchiveRoot(root);
		var store = new FileDocumentArchiveStore(properties);
		var fetch = new OpenDartDocumentFetch(List.of(), List.of(new OpenDartSource(
			DocumentArchiveKind.DART_VIEWER_HTML, DocumentArchiveStatus.VERIFIED,
			"<html><body>검증된 원문</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8), null)));
		var first = store.storeRecovered("20240416000014", fetch).getFirst();
		var same = store.storeRecovered("20240416000014", fetch).getFirst();
		assertThat(same).isEqualTo(first);
		var path = root.resolve(first.relativePath());
		assertThat(first.relativePath()).startsWith("html-repair/20240416000014/");
		assertThat(Files.getPosixFilePermissions(path)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
		Files.write(path, new byte[]{1});
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.storeRecovered("20240416000014", fetch))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("checksum mismatch");
		assertThat(Files.readAllBytes(path)).containsExactly(1);
	}

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
