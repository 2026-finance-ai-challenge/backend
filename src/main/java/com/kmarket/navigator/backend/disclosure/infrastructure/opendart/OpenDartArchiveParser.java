package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
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
	private static final int MAX_ENTRY_BYTES = 128 * 1024 * 1024;
	private static final int MAX_CORPORATION_ENTRY_BYTES = 40 * 1024 * 1024;
	private static final int MAX_TOTAL_BYTES = 128 * 1024 * 1024;
	private static final Charset KOREAN_CHARSET = Charset.forName("MS949");
	private static final byte[] XML_ENTITY_MARKER = "<!ENTITY".getBytes(StandardCharsets.US_ASCII);
	private static final Safelist DISCLOSURE_HTML_SAFELIST = Safelist.relaxed()
		.addTags("article", "section", "main", "header", "footer", "figure", "figcaption",
			"table", "caption", "colgroup", "col", "thead", "tbody", "tfoot", "tr", "th", "td")
		.addAttributes(":all", "class")
		.addAttributes("table", "summary", "width", "border", "cellpadding", "cellspacing")
		.addAttributes("col", "span", "width")
		.addAttributes("th", "colspan", "rowspan", "scope", "headers", "align", "valign", "width")
		.addAttributes("td", "colspan", "rowspan", "headers", "align", "valign", "width")
		.addProtocols("a", "href", "http", "https");

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

	OpenDartDocument parseViewerDocument(String receiptNumber, byte[] content) {
		if (!receiptNumber.matches("[0-9]{14}")) {
			throw new IllegalArgumentException("Invalid receipt number");
		}
		return parseDocument(new ArchiveEntry(receiptNumber + ".viewer.html", content));
	}

	private List<ArchiveEntry> unzip(byte[] archive, int maximumEntryBytes) {
		List<CentralEntry> centralEntries = readCentralEntries(archive);
		List<ArchiveEntry> entries = new ArrayList<>();
		int totalBytes = 0;
		int centralIndex = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (centralIndex >= centralEntries.size()) {
					throw new OpenDartException("INVALID_ARCHIVE");
				}
				CentralEntry centralEntry = centralEntries.get(centralIndex++);
				String normalizedFilename = sanitizeFilename(entry.getName());
				if (!centralEntry.filename().equals(normalizedFilename)) {
					throw new OpenDartException("INVALID_ARCHIVE");
				}
				if (entry.isDirectory()) {
					continue;
				}
				if (entries.size() >= MAX_ENTRIES) {
					throw new OpenDartException("ARCHIVE_ENTRY_LIMIT");
				}
				byte[] content = readLimited(zip, maximumEntryBytes);
				if (centralEntry.size() != content.length || centralEntry.crc() != crc32(content)) {
					throw new OpenDartException("INVALID_ARCHIVE");
				}
				totalBytes += content.length;
				if (totalBytes > MAX_TOTAL_BYTES) {
					throw new OpenDartException("ARCHIVE_SIZE_LIMIT");
				}
				entries.add(new ArchiveEntry(normalizedFilename, content));
			}
		}
		catch (IOException exception) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		if (centralIndex != centralEntries.size()) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		if (entries.isEmpty()) {
			throw new OpenDartException("EMPTY_ARCHIVE");
		}
		return entries;
	}

	private static List<CentralEntry> readCentralEntries(byte[] archive) {
		int endRecord = findEndOfCentralDirectory(archive);
		int totalEntries = unsignedShort(archive, endRecord + 10);
		long centralSize = unsignedInt(archive, endRecord + 12);
		long centralOffset = unsignedInt(archive, endRecord + 16);
		if (totalEntries > MAX_ENTRIES
			|| centralOffset > Integer.MAX_VALUE
			|| centralSize > Integer.MAX_VALUE
			|| centralOffset + centralSize > endRecord) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}

		List<CentralEntry> entries = new ArrayList<>(totalEntries);
		int cursor = (int) centralOffset;
		int centralEnd = cursor + (int) centralSize;
		for (int index = 0; index < totalEntries; index++) {
			if (cursor + 46 > centralEnd || readInt(archive, cursor) != 0x02014B50) {
				throw new OpenDartException("INVALID_ARCHIVE");
			}
			int filenameLength = unsignedShort(archive, cursor + 28);
			int extraLength = unsignedShort(archive, cursor + 30);
			int commentLength = unsignedShort(archive, cursor + 32);
			int recordLength = 46 + filenameLength + extraLength + commentLength;
			if (cursor + recordLength > centralEnd) {
				throw new OpenDartException("INVALID_ARCHIVE");
			}
			String filename = sanitizeFilename(new String(
				archive,
				cursor + 46,
				filenameLength,
				StandardCharsets.UTF_8
			));
			entries.add(new CentralEntry(
				filename,
				unsignedInt(archive, cursor + 16),
				unsignedInt(archive, cursor + 24)
			));
			cursor += recordLength;
		}
		if (cursor != centralEnd) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		return entries;
	}

	private static int findEndOfCentralDirectory(byte[] archive) {
		int first = Math.max(0, archive.length - 65_557);
		for (int index = archive.length - 22; index >= first; index--) {
			if (readInt(archive, index) == 0x06054B50) {
				return index;
			}
		}
		throw new OpenDartException("INVALID_ARCHIVE");
	}

	private static int readInt(byte[] bytes, int offset) {
		if (offset < 0 || offset + 4 > bytes.length) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		return (bytes[offset] & 0xFF)
			| ((bytes[offset + 1] & 0xFF) << 8)
			| ((bytes[offset + 2] & 0xFF) << 16)
			| ((bytes[offset + 3] & 0xFF) << 24);
	}

	private static long unsignedInt(byte[] bytes, int offset) {
		return Integer.toUnsignedLong(readInt(bytes, offset));
	}

	private static int unsignedShort(byte[] bytes, int offset) {
		if (offset < 0 || offset + 2 > bytes.length) {
			throw new OpenDartException("INVALID_ARCHIVE");
		}
		return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
	}

	private static long crc32(byte[] content) {
		CRC32 crc = new CRC32();
		crc.update(content);
		return crc.getValue();
	}

	private record CentralEntry(String filename, long crc, long size) {
	}

	private OpenDartDocument parseDocument(ArchiveEntry entry) {
		rejectXmlEntityDeclarations(entry.content());
		// HTML 파싱 전에 DART 전용 셀을 변환해야 값이 표 밖으로 이동하지 않는다.
		String markup = decodeDocument(entry.content()).replaceAll("(?i)<(/?)(?:TU|TE)(?=[\\s>])", "<$1td");
		Document document = Jsoup.parse(markup);
		List<OpenDartSection> sections = extractSections(document);
		if (sections.stream().anyMatch(section -> section.text().contains("\uFFFD"))) {
			throw new OpenDartException("SOURCE_TEXT_CORRUPTED");
		}
		return new OpenDartDocument(
			entry.filename(),
			sha256(entry.content()),
			document.body() == null ? visibleText(document) : visibleText(document.body()),
			sanitizeHtml(document),
			sections
		);
	}

	private static String sanitizeHtml(Document document) {
		String source = document.body() == null ? document.html() : document.body().html();
		Document.OutputSettings outputSettings = new Document.OutputSettings().prettyPrint(false);
		return Jsoup.clean(source, "", DISCLOSURE_HTML_SAFELIST, outputSettings);
	}

	private List<OpenDartSection> extractSections(Document document) {
		List<OpenDartSection> sections = new ArrayList<>();
		String title = normalize(document.title());
		if (!title.isBlank()) {
			sections.add(new OpenDartSection(sections.size(), SectionKind.TITLE, title, title, null));
		}

		if (document.body() != null) {
			appendVisibleSections(document.body(), sections);
		}
		else {
			addTextSection(sections, normalize(document.text()), false);
		}
		return sections;
	}

	private void appendVisibleSections(Node node, List<OpenDartSection> sections) {
		if (node instanceof TextNode textNode) {
			addTextSection(sections, normalize(textNode.text()), false);
			return;
		}
		if (!(node instanceof Element element)) {
			return;
		}

		String tagName = element.tagName();
		if (tagName.equals("script") || tagName.equals("style") || tagName.equals("noscript")) {
			return;
		}
		if (tagName.equals("table")) {
			String text = visibleText(element);
			if (!text.isBlank()) {
				sections.add(new OpenDartSection(
					sections.size(),
					SectionKind.TABLE,
					null,
					text,
					tableJson(element)
				));
			}
			return;
		}
		if (isAtomicTextElement(tagName)) {
			String text = visibleText(element);
			addTextSection(sections, text, tagName.startsWith("h"));
			return;
		}

		for (Node child : element.childNodes()) {
			appendVisibleSections(child, sections);
		}
	}

	private static String visibleText(Node node) {
		StringBuilder text = new StringBuilder();
		appendVisibleText(node, text);
		return normalize(text.toString());
	}

	private static void appendVisibleText(Node node, StringBuilder text) {
		if (node instanceof TextNode textNode) {
			String value = normalize(textNode.text());
			if (!value.isBlank()) {
				if (!text.isEmpty()) {
					text.append(' ');
				}
				text.append(value);
			}
			return;
		}
		if (node instanceof Element element) {
			String tagName = element.tagName();
			if (tagName.equals("script") || tagName.equals("style") || tagName.equals("noscript")) {
				return;
			}
		}
		for (Node child : node.childNodes()) {
			appendVisibleText(child, text);
		}
	}

	private static boolean isAtomicTextElement(String tagName) {
		return isHeading(tagName)
			|| tagName.equals("p")
			|| tagName.equals("pre")
			|| tagName.equals("li")
			|| tagName.equals("blockquote");
	}

	private static boolean isHeading(String tagName) {
		return tagName.length() == 2
			&& tagName.charAt(0) == 'h'
			&& tagName.charAt(1) >= '1'
			&& tagName.charAt(1) <= '6';
	}

	private static void addTextSection(
		List<OpenDartSection> sections,
		String text,
		boolean heading
	) {
		if (text.isBlank()) {
			return;
		}
		sections.add(new OpenDartSection(
			sections.size(),
			heading ? SectionKind.TITLE : SectionKind.TEXT,
			heading ? text : null,
			text,
			null
		));
	}

	private String tableJson(Element table) {
		List<List<String>> rows = table.select("tr").stream()
			.map(row -> row.children().stream()
				.filter(cell -> cell.tagName().equals("th") || cell.tagName().equals("td"))
				.map(cell -> {
					var own = cell.clone();
					own.select("table").remove();
					return normalize(own.text());
				})
				.toList())
			.filter(row -> !row.isEmpty())
			.toList();
		return objectMapper.writeValueAsString(rows);
	}

	private List<OpenDartCorporation> parseCorporationXml(byte[] content) {
		rejectXmlEntityDeclarations(content);
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

	private static String sanitizeFilename(String filename) {
		String normalized = filename.replace('\\', '/');
		String safeFilename = normalized.replaceFirst("^/+", "");
		if (safeFilename.isBlank()
			|| safeFilename.length() > MAX_FILENAME_LENGTH
			|| safeFilename.equals("..")
			|| safeFilename.startsWith("../")
			|| safeFilename.contains("/../")
			|| safeFilename.endsWith("/..")
			|| safeFilename.contains(":")) {
			throw new OpenDartException("UNSAFE_ARCHIVE_PATH");
		}
		return safeFilename;
	}

	private static String decodeDocument(byte[] content) {
		try {
			return removeBom(decodeStrict(content, StandardCharsets.UTF_8));
		}
		catch (CharacterCodingException ignored) {
			try {
				return removeBom(decodeStrict(content, KOREAN_CHARSET));
			}
			catch (CharacterCodingException exception) {
				throw new OpenDartException("INVALID_DOCUMENT_ENCODING");
			}
		}
	}

	private static String decodeStrict(byte[] content, Charset charset) throws CharacterCodingException {
		return charset.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(content))
			.toString();
	}

	private static String removeBom(String value) {
		return value.startsWith("\uFEFF") ? value.substring(1) : value;
	}

	private static void rejectXmlEntityDeclarations(byte[] content) {
		for (int offset = 0; offset <= content.length - XML_ENTITY_MARKER.length; offset++) {
			boolean matched = true;
			for (int index = 0; index < XML_ENTITY_MARKER.length; index++) {
				int value = content[offset + index] & 0xff;
				if (value >= 'a' && value <= 'z') {
					value -= 'a' - 'A';
				}
				if (value != XML_ENTITY_MARKER[index]) {
					matched = false;
					break;
				}
			}
			if (matched) {
				throw new OpenDartException("UNSAFE_XML");
			}
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
