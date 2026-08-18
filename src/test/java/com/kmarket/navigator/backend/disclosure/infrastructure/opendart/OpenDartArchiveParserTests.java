package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.ObjectMapper;

class OpenDartArchiveParserTests {

	private final OpenDartArchiveParser parser = new OpenDartArchiveParser(new ObjectMapper());

	@Test
	void parsesKoreanDocumentAndPreservesTableStructure() {
		String html = """
			<html>
			<head><meta charset="euc-kr"><title>사업보고서</title></head>
			<body><p>영업이익 증가</p><table><tr><th>항목</th><td>금액</td></tr></table></body>
			</html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(Charset.forName("EUC-KR"))))
			.getFirst();

		assertThat(document.bodyText()).contains("영업이익 증가");
		assertThat(document.sections()).anySatisfy(section -> {
			if (section.kind() == SectionKind.TABLE) {
				assertThat(section.tableData()).isEqualTo("[[\"항목\",\"금액\"]]");
			}
		});
	}

	@Test
	void rejectsUnsafeArchivePath() {
		byte[] archive = zip("../filing.xml", "<html/>".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> parser.parseDocuments(archive))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("UNSAFE_ARCHIVE_PATH"));
	}

	@Test
	void rejectsXmlEntityDeclarations() {
		byte[] document = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><html>&xxe;</html>"
			.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> parser.parseDocuments(zip("filing.xml", document)))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("UNSAFE_XML"));
	}

	@Test
	void rejectsArchiveWithoutXmlDocument() {
		byte[] archive = zip("readme.txt", "not a filing".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> parser.parseDocuments(archive))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("EMPTY_DOCUMENT_ARCHIVE"));
	}

	private static byte[] zip(String filename, byte[] content) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(output)) {
				zip.putNextEntry(new ZipEntry(filename));
				zip.write(content);
				zip.closeEntry();
			}
			return output.toByteArray();
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
