package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import java.time.LocalDate;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveKind;
import com.kmarket.navigator.backend.disclosure.application.port.DocumentArchiveStatus;

import tools.jackson.databind.ObjectMapper;

class OpenDartClientTests {
	@Test
	void collectsAllViewerSectionsAndPreservesOriginalResponses() {
		var apiBuilder = RestClient.builder();
		var viewerBuilder = RestClient.builder();
		var api = MockRestServiceServer.bindTo(apiBuilder).build();
		var viewer = MockRestServiceServer.bindTo(viewerBuilder).build();
		var mapper = new ObjectMapper();
		var props = new OpenDartProperties();
		props.setApiKey("0".repeat(40));
		var client = new OpenDartClient(apiBuilder.baseUrl("https://opendart.fss.or.kr").build(),
			viewerBuilder.baseUrl("https://dart.fss.or.kr").build(), props, new OpenDartArchiveParser(mapper), mapper);
		api.expect(requestTo(containsString("/api/document.xml"))).andRespond(withSuccess(
			"<result><status>014</status></result>", MediaType.APPLICATION_XML));
		String index = DartViewerReferenceParserTests.INITIAL + DartViewerReferenceParserTests.node("1", "100", "200")
			+ DartViewerReferenceParserTests.node("2", "301", "100");
		viewer.expect(requestTo(containsString("/dsaf001/main.do"))).andRespond(withSuccess(index, MediaType.TEXT_HTML));
		String cover = "<html><body><p>표지 원문</p></body></html>";
		String body = "<html><body><h2>마지막 본문</h2><table><tr><td>배정액</td><td>100</td></tr></table></body></html>";
		viewer.expect(requestTo(containsString("eleId=1"))).andRespond(withSuccess(cover, MediaType.TEXT_HTML));
		viewer.expect(requestTo(containsString("eleId=2"))).andRespond(withSuccess(body, MediaType.TEXT_HTML));
		var result = client.fetchDocuments(DartViewerReferenceParserTests.RECEIPT);
		assertThat(result.documents()).singleElement().satisfies(doc -> {
			assertThat(doc.bodyText()).contains("표지 원문", "마지막 본문", "배정액", "100");
			assertThat(org.jsoup.Jsoup.parse(doc.sanitizedHtml()).select("table")).hasSize(1);
		});
		assertThat(result.sources().getFirst().provenance().get("page-2-301.raw")).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
		assertThat(result.sources().getFirst().provenance().get("index.raw")).isEqualTo(index.getBytes(StandardCharsets.UTF_8));
		api.verify(); viewer.verify();
	}

	@Test
	void switchesToNextApiKeyAfterDailyLimitResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		String firstKey = "1".repeat(40);
		String secondKey = "2".repeat(40);
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKeys(List.of(firstKey, secondKey));
		OpenDartClient client = new OpenDartClient(
			builder.baseUrl("https://opendart.fss.or.kr").build(),
			RestClient.builder().baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);

		server.expect(requestTo(containsString(firstKey)))
			.andRespond(withSuccess(
				"{\"status\":\"020\",\"message\":\"일일 사용량을 초과했습니다.\"}",
				MediaType.APPLICATION_JSON
			));
		server.expect(requestTo(containsString(secondKey)))
			.andRespond(withSuccess(
				"{\"status\":\"013\",\"page_no\":1,\"total_page\":0}",
				MediaType.APPLICATION_JSON
			));

		var page = client.fetchFilings(
			LocalDate.of(2026, 8, 18),
			LocalDate.of(2026, 8, 18),
			CorporationClass.KOSPI,
			DisclosureType.MATERIAL_EVENT,
			1
		);

		assertThat(page.filings()).isEmpty();
		server.verify();
	}

	@Test
	void mapsDisclosureListResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			builder.baseUrl("https://opendart.fss.or.kr").build(),
			RestClient.builder().baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);
		server.expect(requestTo(containsString("/api/list.json")))
			.andRespond(withSuccess("""
				{
				  "status": "000",
				  "page_no": 1,
				  "total_page": 1,
				  "list": [{
				    "corp_cls": "Y",
				    "corp_code": "00126380",
				    "corp_name": "삼성전자",
				    "stock_code": "005930",
				    "report_nm": "기업설명회(IR) 개최",
				    "rcept_no": "20260818800670",
				    "flr_nm": "삼성전자",
				    "rcept_dt": "20260818",
				    "rm": ""
				  }]
				}
				""", MediaType.APPLICATION_JSON));

		var page = client.fetchFilings(
			LocalDate.of(2026, 8, 18),
			LocalDate.of(2026, 8, 18),
			CorporationClass.KOSPI,
			DisclosureType.MATERIAL_EVENT,
			1
		);

		assertThat(page.filings()).singleElement().satisfies(filing -> {
			assertThat(filing.receiptNumber()).isEqualTo("20260818800670");
			assertThat(filing.stockCode()).isEqualTo("005930");
			assertThat(filing.filedDate()).isEqualTo(LocalDate.of(2026, 8, 18));
		});
		server.verify();
	}

	@Test
	void fallsBackToOfficialDartViewerWhenOpenDartArchiveIsMissing() {
		RestClient.Builder openDartBuilder = RestClient.builder();
		RestClient.Builder viewerBuilder = RestClient.builder();
		MockRestServiceServer openDartServer = MockRestServiceServer.bindTo(openDartBuilder).build();
		MockRestServiceServer viewerServer = MockRestServiceServer.bindTo(viewerBuilder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			openDartBuilder.baseUrl("https://opendart.fss.or.kr").build(),
			viewerBuilder.baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);
		openDartServer.expect(requestTo(containsString("/api/document.xml")))
			.andRespond(withSuccess(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?><result><status>014</status>"
					+ "<message>파일이 존재하지 않습니다.</message></result>",
				MediaType.APPLICATION_XML
			));
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withSuccess(
				"viewDoc(\"20260818000021\", \"11506303\", \"0\", \"0\", \"0\", \"dart4.xsd\", \"\")",
				MediaType.TEXT_HTML
			));
		viewerServer.expect(requestTo(containsString("/report/viewer.do")))
			.andRespond(withSuccess(
				"<html><body><p>웹 뷰어에 존재하는 공시 원문</p></body></html>",
				MediaType.TEXT_HTML
			));

		var documents = client.fetchDocuments("20260818000021").documents();

		assertThat(documents).singleElement().satisfies(document ->
			assertThat(document.bodyText()).contains("웹 뷰어에 존재하는 공시 원문"));
		openDartServer.verify();
		viewerServer.verify();
	}

	@Test
	void distinguishesDartViewerNetworkFailureFromOpenDartFailure() {
		RestClient.Builder openDartBuilder = RestClient.builder();
		RestClient.Builder viewerBuilder = RestClient.builder();
		MockRestServiceServer openDartServer = MockRestServiceServer.bindTo(openDartBuilder).build();
		MockRestServiceServer viewerServer = MockRestServiceServer.bindTo(viewerBuilder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			openDartBuilder.baseUrl("https://opendart.fss.or.kr").build(),
			viewerBuilder.baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);
		openDartServer.expect(requestTo(containsString("/api/document.xml")))
			.andRespond(withSuccess(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?><result><status>014</status>"
					+ "<message>파일이 존재하지 않습니다.</message></result>",
				MediaType.APPLICATION_XML
			));
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withServerError());
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withServerError());
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withServerError());

		assertThatThrownBy(() -> client.fetchDocuments("20260818000021"))
			.isInstanceOfSatisfying(OpenDartException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo("DART_VIEWER_NETWORK_ERROR"));
		openDartServer.verify();
		viewerServer.verify();
	}

	@Test
	void fallsBackToOfficialDartViewerWhenOpenDartSourceTextIsCorrupted() {
		RestClient.Builder openDartBuilder = RestClient.builder();
		RestClient.Builder viewerBuilder = RestClient.builder();
		MockRestServiceServer openDartServer = MockRestServiceServer.bindTo(openDartBuilder).build();
		MockRestServiceServer viewerServer = MockRestServiceServer.bindTo(viewerBuilder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			openDartBuilder.baseUrl("https://opendart.fss.or.kr").build(),
			viewerBuilder.baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);
		openDartServer.expect(requestTo(containsString("/api/document.xml")))
			.andRespond(withSuccess(
				zip("filing.xml", "<html><body><p>손상된 � 원문</p></body></html>"
					.getBytes(StandardCharsets.UTF_8)),
				MediaType.APPLICATION_OCTET_STREAM
			));
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withSuccess(
				"viewDoc(\"20010321000241\", \"159696\", \"0\", \"0\", \"0\", \"dart2.dtd\", \"\")",
				MediaType.TEXT_HTML
			));
		viewerServer.expect(requestTo(containsString("/report/viewer.do")))
			.andRespond(withSuccess(
				"<html><body><p>기타 주요경영사항에 대한 공시</p></body></html>",
				MediaType.TEXT_HTML
			));

		var fetch = client.fetchDocuments("20010321000241");
		var documents = fetch.documents();

		assertThat(documents).singleElement().satisfies(document -> {
			assertThat(document.filename()).isEqualTo("20010321000241.viewer.html");
			assertThat(document.bodyText()).contains("기타 주요경영사항에 대한 공시");
			assertThat(document.bodyText()).doesNotContain("�");
		});
		assertThat(fetch.sources()).hasSize(2)
			.anySatisfy(source -> assertThat(source.status()).isEqualTo(DocumentArchiveStatus.REJECTED))
			.anySatisfy(source -> assertThat(source.kind()).isEqualTo(DocumentArchiveKind.DART_VIEWER_HTML));
		openDartServer.verify();
		viewerServer.verify();
	}

	@Test
	void fetchesVerifiedViewerInsteadOfStoringAnEmptyXmlDocument() {
		var api = RestClient.builder();
		var viewer = RestClient.builder();
		var apiServer = MockRestServiceServer.bindTo(api).build();
		var viewerServer = MockRestServiceServer.bindTo(viewer).build();
		var mapper = new ObjectMapper();
		var properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		var client = new OpenDartClient(api.baseUrl("https://opendart.fss.or.kr").build(),
			viewer.baseUrl("https://dart.fss.or.kr").build(), properties, new OpenDartArchiveParser(mapper), mapper);
		apiServer.expect(requestTo(containsString("/api/document.xml"))).andRespond(withSuccess(zip("20240416000014.xml",
			"<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n\r\n\r\n".getBytes(StandardCharsets.UTF_8)), MediaType.APPLICATION_OCTET_STREAM));
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do"))).andRespond(withSuccess(
			"viewDoc(\"20240416000014\", \"12345\", \"0\", \"0\", \"0\", \"dart4.xsd\", \"\")", MediaType.TEXT_HTML));
		viewerServer.expect(requestTo(containsString("/report/viewer.do"))).andRespond(withSuccess(
			"<table><tr><td>발행금액</td><td>100,000,000</td></tr></table>", MediaType.TEXT_HTML));
		var fetched = client.fetchDocuments("20240416000014");
		assertThat(fetched.documents()).singleElement().satisfies(doc -> {
			assertThat(doc.filename()).isEqualTo("20240416000014.viewer.html");
			assertThat(doc.sanitizedHtml()).contains("<table>");
			assertThat(doc.sections()).isNotEmpty();
		});
		assertThat(fetched.sources()).anySatisfy(source -> {
			assertThat(source.kind()).isEqualTo(DocumentArchiveKind.OPENDART_ZIP);
			assertThat(source.status()).isEqualTo(DocumentArchiveStatus.REJECTED);
			assertThat(source.errorCode()).isEqualTo("EMPTY_DOCUMENT_CONTENT");
		});
		apiServer.verify(); viewerServer.verify();
	}

	@Test
	void fallsBackToOfficialDartViewerWhenOpenDartArchiveIsMalformed() {
		RestClient.Builder openDartBuilder = RestClient.builder();
		RestClient.Builder viewerBuilder = RestClient.builder();
		MockRestServiceServer openDartServer = MockRestServiceServer.bindTo(openDartBuilder).build();
		MockRestServiceServer viewerServer = MockRestServiceServer.bindTo(viewerBuilder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			openDartBuilder.baseUrl("https://opendart.fss.or.kr").build(),
			viewerBuilder.baseUrl("https://dart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);

		openDartServer.expect(requestTo(containsString("/api/document.xml")))
			.andRespond(withSuccess(malformedZip(), MediaType.APPLICATION_OCTET_STREAM));
		viewerServer.expect(requestTo(containsString("/dsaf001/main.do")))
			.andRespond(withSuccess(
				"viewDoc(\"20010321000242\", \"159696\", \"0\", \"0\", \"0\", \"dart2.dtd\", \"\")",
				MediaType.TEXT_HTML
			));
		viewerServer.expect(requestTo(containsString("/report/viewer.do")))
			.andRespond(withSuccess(
				"<html><body><p>웹 뷰어 대체 원문</p></body></html>",
				MediaType.TEXT_HTML
			));

		var fetch = client.fetchDocuments("20010321000242");

		assertThat(fetch.documents()).singleElement().satisfies(document ->
			assertThat(document.bodyText()).contains("웹 뷰어 대체 원문"));
		assertThat(fetch.sources()).anySatisfy(source ->
			assertThat(source.status()).isEqualTo(DocumentArchiveStatus.REJECTED));
		openDartServer.verify();
		viewerServer.verify();
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

	private static byte[] malformedZip() {
		byte[] content = "<html><body><p>손상된 압축</p></body></html>".getBytes(StandardCharsets.UTF_8);
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
			java.util.Arrays.fill(archive, 18, 26, (byte) 0);
			return archive;
		}
		catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
