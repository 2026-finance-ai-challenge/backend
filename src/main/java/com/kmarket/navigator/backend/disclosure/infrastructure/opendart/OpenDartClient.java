package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.application.port.OpenDartCorporation;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartDocument;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartFiling;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartGateway;
import com.kmarket.navigator.backend.disclosure.application.port.OpenDartPage;
import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class OpenDartClient implements OpenDartGateway {

	private static final DateTimeFormatter DART_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final int PAGE_SIZE = 100;
	private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
	private static final int MAX_RESPONSE_BYTES = 25 * 1024 * 1024;
	private static final int MAX_ERROR_RESPONSE_BYTES = 64 * 1024;
	private static final int MAX_ATTEMPTS = 3;

	private final RestClient restClient;
	private final OpenDartProperties properties;
	private final OpenDartArchiveParser archiveParser;
	private final ObjectMapper objectMapper;

	OpenDartClient(
		RestClient openDartRestClient,
		OpenDartProperties properties,
		OpenDartArchiveParser archiveParser,
		ObjectMapper objectMapper
	) {
		this.restClient = openDartRestClient;
		this.properties = properties;
		this.archiveParser = archiveParser;
		this.objectMapper = objectMapper;
	}

	@Override
	public OpenDartPage fetchFilings(
		LocalDate from,
		LocalDate to,
		CorporationClass corporationClass,
		DisclosureType disclosureType,
		int page
	) {
		byte[] body = executeWithRetry(() -> restClient.get()
			.uri(builder -> builder
				.path("/api/list.json")
				.queryParam("crtfc_key", properties.apiKey())
				.queryParam("bgn_de", DART_DATE.format(from))
				.queryParam("end_de", DART_DATE.format(to))
				.queryParam("corp_cls", corporationClass.code())
				.queryParam("pblntf_ty", disclosureType.code())
				.queryParam("page_no", page)
				.queryParam("page_count", PAGE_SIZE)
				.build())
			.exchange((request, response) -> readResponse(response, MAX_JSON_RESPONSE_BYTES)));

		JsonNode root = parseJson(body);
		String status = text(root, "status");
		if (status.equals("013")) {
			return new OpenDartPage(List.of(), page, 0);
		}
		if (!status.equals("000")) {
			throw new OpenDartException("STATUS_" + status);
		}

		List<OpenDartFiling> filings = new ArrayList<>();
		for (JsonNode item : root.path("list")) {
			filings.add(new OpenDartFiling(
				text(item, "rcept_no"),
				text(item, "corp_code"),
				text(item, "corp_name").trim(),
				nullIfBlank(text(item, "stock_code")),
				CorporationClass.fromCode(text(item, "corp_cls")),
				disclosureType,
				text(item, "report_nm").trim(),
				text(item, "flr_nm").trim(),
				LocalDate.parse(text(item, "rcept_dt"), DART_DATE),
				text(item, "rm").trim()
			));
		}
		return new OpenDartPage(filings, root.path("page_no").asInt(page), root.path("total_page").asInt(1));
	}

	@Override
	public List<OpenDartCorporation> fetchListedCorporations() {
		byte[] archive = fetchArchive("/api/corpCode.xml", null);
		return archiveParser.parseCorporations(archive);
	}

	@Override
	public List<OpenDartDocument> fetchDocuments(String receiptNumber) {
		if (!receiptNumber.matches("[0-9]{14}")) {
			throw new IllegalArgumentException("Invalid receipt number");
		}
		byte[] archive = fetchArchive("/api/document.xml", receiptNumber);
		return archiveParser.parseDocuments(archive);
	}

	private byte[] fetchArchive(String path, String receiptNumber) {
		byte[] archive = executeWithRetry(() -> restClient.get()
			.uri(builder -> {
				var uri = builder
					.path(path)
					.queryParam("crtfc_key", properties.apiKey());
				if (receiptNumber != null) {
					uri.queryParam("rcept_no", receiptNumber);
				}
				return uri.build();
			})
			.exchange((request, response) -> readResponse(response, MAX_RESPONSE_BYTES)));
		validateArchiveResponse(archive);
		return archive;
	}

	private static void validateArchiveResponse(byte[] response) {
		if (response.length >= 4 && response[0] == 'P' && response[1] == 'K') {
			return;
		}
		if (response.length > MAX_ERROR_RESPONSE_BYTES) {
			throw new OpenDartException("INVALID_ARCHIVE_RESPONSE");
		}

		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
		try {
			XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(response));
			while (reader.hasNext()) {
				if (reader.next() == XMLStreamConstants.START_ELEMENT
					&& reader.getLocalName().equals("status")) {
					String status = reader.getElementText().trim();
					reader.close();
					if (status.matches("[0-9]{3}")) {
						throw new OpenDartException("STATUS_" + status);
					}
					break;
				}
			}
			reader.close();
		}
		catch (XMLStreamException exception) {
			throw new OpenDartException("INVALID_ARCHIVE_RESPONSE");
		}
		throw new OpenDartException("INVALID_ARCHIVE_RESPONSE");
	}

	private static byte[] readResponse(ClientHttpResponse response, int maximumBytes) {
		try {
			HttpStatusCode status = response.getStatusCode();
			if (!status.is2xxSuccessful()) {
				throw new RestClientException("OpenDART HTTP request failed");
			}
			byte[] content = response.getBody().readNBytes(maximumBytes + 1);
			if (content.length > maximumBytes) {
				throw new OpenDartException("RESPONSE_SIZE_LIMIT");
			}
			return content;
		}
		catch (IOException exception) {
			throw new OpenDartException("RESPONSE_READ_FAILED");
		}
	}

	private JsonNode parseJson(byte[] body) {
		try {
			return objectMapper.readTree(body);
		}
		catch (RuntimeException exception) {
			throw new OpenDartException("INVALID_JSON");
		}
	}

	private <T> T executeWithRetry(Supplier<T> request) {
		RestClientException lastException = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				T response = request.get();
				if (response == null) {
					throw new OpenDartException("EMPTY_RESPONSE");
				}
				return response;
			}
			catch (RestClientException exception) {
				lastException = exception;
				if (attempt < MAX_ATTEMPTS) {
					pause(attempt);
				}
			}
		}
		throw new OpenDartException(lastException == null ? "REQUEST_FAILED" : "NETWORK_ERROR");
	}

	private static void pause(int attempt) {
		try {
			Thread.sleep(Duration.ofMillis(250L * attempt));
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new OpenDartException("INTERRUPTED");
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return "";
		}
		return value.asString();
	}

	private static String nullIfBlank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
