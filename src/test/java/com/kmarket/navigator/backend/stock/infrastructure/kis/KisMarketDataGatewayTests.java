package com.kmarket.navigator.backend.stock.infrastructure.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KisMarketDataGatewayTests {

	@Test
	void mapsOfficialCurrentPriceAndIndexContractsWithoutInventingTradingStatus() {
		KisMarketProperties properties = new KisMarketProperties();
		properties.setEnabled(true);
		properties.setAppKey("app-key");
		properties.setAppSecret("app-secret");
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.baseUrl("https://example.test").build();
		KisAccessTokenProvider tokenProvider = mock(KisAccessTokenProvider.class);
		when(tokenProvider.accessToken()).thenReturn("access-token");
		KisMarketDataGateway gateway = new KisMarketDataGateway(
			restClient,
			properties,
			tokenProvider,
			new KisCircuitBreaker()
		);

		server.expect(queryParam("FID_COND_MRKT_DIV_CODE", "J"))
			.andExpect(queryParam("FID_INPUT_ISCD", "005930"))
			.andExpect(header("authorization", "Bearer access-token"))
			.andExpect(header("tr_id", "FHKST01010100"))
			.andRespond(withSuccess("""
				{
				  "rt_cd": "0",
				  "output": {
				    "stck_prpr": "78000", "prdy_vrss": "1200", "prdy_ctrt": "1.5625",
				    "prdy_vrss_sign": "2", "stck_oprc": "77000", "stck_hgpr": "78500",
				    "stck_lwpr": "76800", "acml_vol": "15000000", "stck_mxpr": "99000",
				    "stck_llam": "53000", "temp_stop_yn": "N"
				  }
				}
				""", MediaType.APPLICATION_JSON));
		server.expect(queryParam("FID_COND_MRKT_DIV_CODE", "U"))
			.andExpect(queryParam("FID_INPUT_ISCD", "0001"))
			.andExpect(header("tr_id", "FHPUP02100000"))
			.andRespond(withSuccess("""
				{
				  "rt_cd": "0",
				  "output": {
				    "bstp_nmix_prpr": "2850.50", "bstp_nmix_prdy_vrss": "10.20",
				    "bstp_nmix_prdy_ctrt": "0.3591", "prdy_vrss_sign": "2",
				    "acml_vol": "500000000"
				  }
				}
				""", MediaType.APPLICATION_JSON));

		var quote = gateway.fetchQuote("005930").orElseThrow();
		assertThat(quote.currentPriceKrw()).isEqualByComparingTo(new BigDecimal("78000"));
		assertThat(quote.changeRate()).isEqualByComparingTo(new BigDecimal("1.5625"));
		assertThat(quote.statusAvailable()).isFalse();
		assertThat(quote.viActive()).isNull();
		assertThat(quote.source()).isEqualTo("KIS_REST_CURRENT_PRICE");

		var index = gateway.fetchIndex("0001").orElseThrow();
		assertThat(index.indexName()).isEqualTo("KOSPI");
		assertThat(index.currentValue()).isEqualByComparingTo(new BigDecimal("2850.50"));
		assertThat(index.source()).isEqualTo("KIS_REST_INDEX_PRICE");
		server.verify();
	}
}
