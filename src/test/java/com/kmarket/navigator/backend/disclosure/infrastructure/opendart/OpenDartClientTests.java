package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.domain.CorporationClass;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureType;

import tools.jackson.databind.ObjectMapper;

class OpenDartClientTests {

	@Test
	void mapsDisclosureListResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			builder.baseUrl("https://opendart.fss.or.kr").build(),
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
	void mapsOpenDartArchiveErrorCode() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ObjectMapper objectMapper = new ObjectMapper();
		OpenDartProperties properties = new OpenDartProperties();
		properties.setApiKey("0".repeat(40));
		OpenDartClient client = new OpenDartClient(
			builder.baseUrl("https://opendart.fss.or.kr").build(),
			properties,
			new OpenDartArchiveParser(objectMapper),
			objectMapper
		);
		server.expect(requestTo(containsString("/api/document.xml")))
			.andRespond(withSuccess(
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?><result><status>014</status>"
					+ "<message>파일이 존재하지 않습니다.</message></result>",
				MediaType.APPLICATION_XML
			));

		assertThatThrownBy(() -> client.fetchDocuments("20260818000021"))
			.isInstanceOfSatisfying(OpenDartException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo("STATUS_014"));
		server.verify();
	}
}
