package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.ObjectMapper;

class OpenDartArchiveParserTests {

	private final OpenDartArchiveParser parser = new OpenDartArchiveParser(new ObjectMapper());

	@Test
	void preservesDartXmlNumericCellsAndNestedTableText() {
		String html = "<TABLE><TR><TU ROWSPAN='2'>100</TU><TE>매출<TABLE><TR><TD>이익</TD></TR></TABLE></TE></TR></TABLE>";
		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8))).getFirst();
		assertThat(document.sections().getFirst().tableData()).isEqualTo("[[\"100\",\"매출\"],[\"이익\"]]");
		assertThat(document.sanitizedHtml()).contains("<td rowspan=\"2\">100</td>");
	}

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
		assertThat(document.sanitizedHtml())
			.contains("<table>", "<th>항목</th>", "<td>금액</td>");
		assertThat(document.sections()).anySatisfy(section -> {
			if (section.kind() == SectionKind.TABLE) {
				assertThat(section.tableData()).isEqualTo("[[\"항목\",\"금액\"]]");
			}
		});
	}

	@Test
	void sanitizesExecutableMarkupAndPreservesMergedTableCells() {
		String html = """
			<html><body onload="steal()"><script>alert(1)</script>
			<table><tr><th rowspan="2" colspan="3" onclick="steal()">항목</th></tr></table>
			<a href="javascript:steal()">링크</a></body></html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.sanitizedHtml())
			.contains("rowspan=\"2\"", "colspan=\"3\"")
			.doesNotContain("script", "onclick", "onload", "javascript:");
	}

	@Test
	void parsesUtf8DocumentWhenMetaIncorrectlyDeclaresEucKr() {
		String html = """
			<html>
			<head><meta charset="euc-kr"><title>주요사항보고서</title></head>
			<body><p>자기주식 처분 결정</p></body>
			</html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.bodyText()).contains("자기주식 처분 결정");
		assertThat(document.bodyText()).doesNotContain("�");
	}

	@Test
	void allowsStandardHtmlDoctype() {
		String html = """
			<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN"
			  "http://www.w3.org/TR/html4/loose.dtd">
			<html><body><p>유상증자 결정</p></body></html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.bodyText()).contains("유상증자 결정");
	}

	@Test
	void preservesVisibleTextOutsideParagraphsAndTablesInDocumentOrder() {
		String html = """
			<html><body>
			<div>앞부분 <span>중간부분</span> 뒷부분</div>
			<custom-tag>사용자 정의 태그 내용</custom-tag>
			</body></html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.sections())
			.extracting(section -> section.text())
			.containsSubsequence("앞부분", "중간부분", "뒷부분", "사용자 정의 태그 내용");
	}

	@Test
	void preservesWhitespaceBetweenAdjacentTextNodes() {
		String html = """
			<html><body><p><span>3. 참조방법</span>금융위원회 홈페이지</p></body></html>
			""";

		var document = parser.parseDocuments(zip("filing.xml", html.getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.sections())
			.extracting(section -> section.text())
			.contains("3. 참조방법 금융위원회 홈페이지");
	}

	@Test
	void rejectsReplacementCharactersFromSourceDocument() {
		byte[] document = "<html><body><p>손상된 � 원문</p></body></html>".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> parser.parseDocuments(zip("filing.xml", document)))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("SOURCE_TEXT_CORRUPTED"));
	}

	@Test
	void rejectsUnsafeArchivePath() {
		byte[] archive = zip("../filing.xml", "<html/>".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> parser.parseDocuments(archive))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("UNSAFE_ARCHIVE_PATH"));
	}

	@Test
	void normalizesLegacyLeadingSlashInArchiveFilename() {
		var document = parser.parseDocuments(zip("/filing.xml",
			"<html><body><p>레거시 공시</p></body></html>".getBytes(StandardCharsets.UTF_8)))
			.getFirst();

		assertThat(document.filename()).isEqualTo("filing.xml");
		assertThat(document.bodyText()).contains("레거시 공시");
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

	@Test
	void rejectsArchiveWhenLocalEntryMetadataDiffersFromCentralDirectory() {
		assertThatThrownBy(() -> parser.parseDocuments(malformedLocalHeaderZip()))
			.isInstanceOfSatisfying(OpenDartException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo("INVALID_ARCHIVE"));
	}

	@Test
	void acceptsDocumentEntryLargerThanLegacyLimit() {
		byte[] prefix = "<html><body><p>".getBytes(StandardCharsets.UTF_8);
		byte[] suffix = "</p></body></html>".getBytes(StandardCharsets.UTF_8);
		byte[] content = new byte[51 * 1024 * 1024];
		Arrays.fill(content, (byte)'a');
		System.arraycopy(prefix, 0, content, 0, prefix.length);
		System.arraycopy(suffix, 0, content, content.length - suffix.length, suffix.length);

		var document = parser.parseDocuments(zip("filing.xml", content)).getFirst();

		assertThat(document.bodyText()).startsWith("a");
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

	private static byte[] malformedLocalHeaderZip() {
		byte[] content = "<html><body><p>원문</p></body></html>".getBytes(StandardCharsets.UTF_8);
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ZipEntry entry = new ZipEntry("filing.xml");
			CRC32 crc = new CRC32();
			crc.update(content);
			entry.setMethod(ZipEntry.STORED);
			entry.setSize(content.length);
			entry.setCompressedSize(content.length);
			entry.setCrc(crc.getValue());
			try (ZipOutputStream zip = new ZipOutputStream(output)) {
				zip.putNextEntry(entry);
				zip.write(content);
				zip.closeEntry();
			}
			byte[] archive = output.toByteArray();
			Arrays.fill(archive, 18, 26, (byte) 0);
			return archive;
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
