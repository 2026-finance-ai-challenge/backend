package com.kmarket.navigator.backend.stock.infrastructure.krx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;

import tools.jackson.databind.ObjectMapper;

class KrxForeignOwnershipGatewayTests {

	@Test
	void logsInAndMapsForeignOwnershipWithoutInventingMissingValues() {
		KrxForeignOwnershipProperties properties = new KrxForeignOwnershipProperties();
		properties.setEnabled(true);
		properties.setBaseUrl(java.net.URI.create("https://data.krx.example"));
		properties.setMemberId("test-user");
		properties.setPassword("test-password");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.baseUrl(properties.getBaseUrl().toString()).build();
		KrxForeignOwnershipGateway gateway = new KrxForeignOwnershipGateway(
			restClient,
			properties,
			new ObjectMapper()
		);

		server.expect(requestTo("https://data.krx.example" + properties.getLoginPath()))
			.andExpect(content().string(containsString("mbrId=test-user")))
			.andExpect(content().string(containsString("pw=test-password")))
			.andRespond(withSuccess("{\"MBR_NO\":\"100000\"}", MediaType.TEXT_HTML)
				.header(HttpHeaders.SET_COOKIE, "JSESSIONID=session-1; Path=/; HttpOnly"));
		server.expect(requestTo("https://data.krx.example/comm/bldAttendant/getJsonData.cmd"))
			.andExpect(header(HttpHeaders.COOKIE, containsString("JSESSIONID=session-1")))
			.andExpect(content().string(containsString("isuCd=KR7003490000")))
			.andExpect(content().string(containsString("strtDd=20250801")))
			.andExpect(content().string(containsString("endDd=20250802")))
			.andRespond(withSuccess("""
				{
				  "output": [
				    {
				      "TRD_DD": "2025/08/01", "LIST_SHRS": "347,820,825",
				      "FORN_HD_QTY": "55,000,000", "FORN_ORD_LMT_QTY": "173,910,412",
				      "FORN_SHR_RT": "15.81", "FORN_LMT_EXHST_RT": "31.63"
				    },
				    {
				      "TRD_DD": "20250802", "FORN_HD_QTY": "55,100,000",
				      "FORN_SHR_RT": "15.84", "FORN_LMT_EXHST_RT": "-"
				    }
				  ]
				}
				""", MediaType.TEXT_HTML));

		var snapshots = gateway.fetchHistory(
			new ForeignLimitCollectionTarget("003490", "KR7003490000"),
			LocalDate.of(2025, 8, 1),
			LocalDate.of(2025, 8, 2)
		);

		assertThat(snapshots).hasSize(2);
		assertThat(snapshots.getFirst().totalListedQuantity()).isEqualTo(347_820_825L);
		assertThat(snapshots.getFirst().availableQuantity()).isEqualTo(118_910_412L);
		assertThat(snapshots.getFirst().limitExhaustionRate()).isEqualByComparingTo("31.63");
		assertThat(snapshots.getLast().foreignLimitQuantity()).isNull();
		assertThat(snapshots.getLast().availableQuantity()).isNull();
		assertThat(snapshots.getLast().limitExhaustionRate()).isNull();
		server.verify();
	}
}
