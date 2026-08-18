package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.port.OpenDartCorporation;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

import tools.jackson.databind.ObjectMapper;

@Component
class OpenDartArchiveParser {

	private static final int MAX_ENTRIES = 100;
	private static final int MAX_FILENAME_LENGTH = 500;
	private static final int MAX_ENTRY_BYTES = 15 * 1024 * 1024;
	private static final int MAX_CORPORATION_ENTRY_BYTES = 40 * 1024 * 1024;
	private static final int MAX_TOTAL_BYTES = 50 * 1024 * 1024;

	private final ObjectMapper objectMapper;

	OpenDartArchiveParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	List<OpenDartCorporation> parseCorporations(byte[] archive) {
		List<ArchiveEntry> entries = unzip(archive, MAX_CORPORATION_ENTRY_BYTES);
		if (entries.size() != 1) {
			throw new OpenDartException("INVALID_CORPORATION_ARCHIVE");
		}
		return parseCorporationXml(entries.getFirst().content());
	}

	List<OpenDartDocument> parseDocuments(byte[] archive) {
		List<OpenDartDocument> documents = unzip(archive, MAX_ENTRY_BYTES).stream()
			.filter(entry -> entry.filename().toLowerCase(Locale.ROOT).endsWith(".xml"))
			.map(this::parseDocument)
			.toList();
		if (documents.isEmpty()) {
			throw new OpenDartException("EMPTY_DOCUMENT_ARCHIVE");
		}
		return documents;
	}

	private List<ArchiveEntry> unzip(byte[] archive, int maximumEntryBytes) {
		List<ArchiveEntry> entries = new ArrayList<>();
		int totalBytes = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				validateFilename(entry.getName());
				if (entries.size() >= MAX_ENTRIES) {
					throw new OpenDartException("ARCHIVE_ENTRY_LIMIT");
				}
				byte[] content = readLimited(zip, maximumEntryBytes);
				totalBytes += content.length;
				if (totalBytes > MAX_TOTAL_BYTES) {
					throw new OpenDartException("ARCHIVE_SIZE_LIMIT");
				}
				entries.add(new ArchiveEntry(entry.getName(), content));
			}
		}
		catch (IOException exception) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		if (entries.isEmpty()) {
			throw new OpenDartException("EMPTY_ARCHIVE");
		}
		return entries;
	}

	private OpenDartDocument parseDocument(ArchiveEntry entry) {
		rejectXmlEntities(entry.content());
		try {
			Document document = Jsoup.parse(new ByteArrayInputStream(entry.content()), null, "");
			List<OpenDartSection> sections = extractSections(document);
			return new OpenDartDocument(
				entry.filename(),
				sha256(entry.content()),
				document.body() == null ? document.text() : document.body().text(),
				sections
			);
		}
		catch (IOException exception) {
			throw new OpenDartException("INVALID_DOCUMENT");
		}
	}

	private List<OpenDartSection> extractSections(Document document) {
		List<OpenDartSection> sections = new ArrayList<>();
		String title = normalize(document.title());
		if (!title.isBlank()) {
			sections.add(new OpenDartSection(sections.size(), SectionKind.TITLE, title, title, null));
		}

		for (Element element : document.select("h1, h2, h3, h4, h5, h6, p, table")) {
			if (element.tagName().equals("table")) {
				String text = normalize(element.text());
				if (!text.isBlank()) {
					sections.add(new OpenDartSection(
						sections.size(),
						SectionKind.TABLE,
						null,
						text,
						tableJson(element)
					));
				}
			}
			else {
				String text = normalize(element.text());
				if (!text.isBlank()) {
					sections.add(new OpenDartSection(
						sections.size(),
						element.tagName().startsWith("h") ? SectionKind.TITLE : SectionKind.TEXT,
						element.tagName().startsWith("h") ? text : null,
						text,
						null
					));
				}
			}
		}

		if (sections.isEmpty() && document.body() != null) {
			String text = normalize(document.body().text());
			if (!text.isBlank()) {
				sections.add(new OpenDartSection(0, SectionKind.TEXT, null, text, null));
			}
		}
		return sections;
	}

	private String tableJson(Element table) {
		List<List<String>> rows = table.select("tr").stream()
			.map(row -> row.children().stream()
				.filter(cell -> cell.tagName().equals("th") || cell.tagName().equals("td"))
				.map(cell -> normalize(cell.text()))
				.toList())
			.filter(row -> !row.isEmpty())
			.toList();
		return objectMapper.writeValueAsString(rows);
	}

	private List<OpenDartCorporation> parseCorporationXml(byte[] content) {
		rejectXmlEntities(content);
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
		List<OpenDartCorporation> corporations = new ArrayList<>();
		try {
			XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(content));
			String corpCode = null;
			String nameKo = null;
			String nameEn = null;
			String stockCode = null;
			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
						case "corp_code" -> corpCode = normalize(reader.getElementText());
						case "corp_name" -> nameKo = normalize(reader.getElementText());
						case "corp_eng_name" -> nameEn = normalize(reader.getElementText());
						case "stock_code" -> stockCode = normalize(reader.getElementText());
						default -> {
						}
					}
				}
				else if (event == XMLStreamConstants.END_ELEMENT && reader.getLocalName().equals("list")) {
					if (corpCode != null && nameKo != null && stockCode != null
						&& stockCode.matches("[0-9A-Z]{6}")) {
						corporations.add(new OpenDartCorporation(corpCode, nameKo, blankToNull(nameEn), stockCode));
					}
					corpCode = null;
					nameKo = null;
					nameEn = null;
					stockCode = null;
				}
			}
			reader.close();
		}
		catch (XMLStreamException exception) {
			throw new OpenDartException("INVALID_CORPORATION_XML");
		}
		return corporations;
	}

	private static byte[] readLimited(ZipInputStream zip, int limit) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = zip.read(buffer)) != -1) {
			total += read;
			if (total > limit) {
				throw new OpenDartException("ARCHIVE_ENTRY_SIZE_LIMIT");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static void validateFilename(String filename) {
		String normalized = filename.replace('\\', '/');
		if (normalized.length() > MAX_FILENAME_LENGTH
			|| normalized.startsWith("/")
			|| normalized.contains("../")
			|| normalized.contains(":")) {
			throw new OpenDartException("UNSAFE_ARCHIVE_PATH");
		}
	}

	private static void rejectXmlEntities(byte[] content) {
		int length = Math.min(content.length, 4096);
		String prefix = new String(content, 0, length, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
		if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY")) {
			throw new OpenDartException("UNSAFE_XML");
		}
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable");
		}
	}

	private static String normalize(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").trim();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private record ArchiveEntry(String filename, byte[] content) {
		private ArchiveEntry {
			content = content.clone();
		}

		@Override
		public byte[] content() {
			return content.clone();
		}
	}
}
