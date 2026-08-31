package com.kmarket.navigator.backend.stock.infrastructure.kis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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

	@Test
	void mapsOfficialCurrentPriceForeignOwnershipFields() {
		KisMarketProperties properties = configuredProperties();
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisAccessTokenProvider tokenProvider = mock(KisAccessTokenProvider.class);
		when(tokenProvider.accessToken()).thenReturn("access-token");
		KisMarketDataGateway gateway = new KisMarketDataGateway(
			builder.baseUrl("https://example.test").build(),
			properties,
			tokenProvider,
			new KisCircuitBreaker()
		);

		server.expect(header("tr_id", "FHKST01010100"))
			.andExpect(queryParam("FID_INPUT_ISCD", "003490"))
			.andRespond(withSuccess("""
				{
				  "rt_cd":"0",
				  "output": {
				    "frgn_hldn_qty":"61250000",
				    "lstn_stcn":"250000000",
				    "hts_frgn_ehrt":"50.0000"
				  }
				}
				""", MediaType.APPLICATION_JSON));

		var ownership = gateway.fetchForeignOwnership("003490").orElseThrow();
		assertThat(ownership.foreignOwnedQuantity()).isEqualTo(61_250_000L);
		assertThat(ownership.totalListedQuantity()).isEqualTo(250_000_000L);
		assertThat(ownership.foreignLimitQuantity()).isEqualTo(122_500_000L);
		assertThat(ownership.availableQuantity()).isEqualTo(61_250_000L);
		assertThat(ownership.ownershipRate()).isEqualByComparingTo("24.5000");
		assertThat(ownership.limitExhaustionRate()).isEqualByComparingTo("50.0000");
		assertThat(ownership.source()).isEqualTo("KIS_REST_CURRENT_PRICE");
		server.verify();
	}

	@Test
	void retriesTransientServerErrorsAndReturnsTheRecoveredQuote() {
		KisMarketProperties properties = configuredProperties();
		properties.setRetryInitialDelay(java.time.Duration.ZERO);
		properties.setRetryMaxDelay(java.time.Duration.ZERO);
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisAccessTokenProvider tokenProvider = mock(KisAccessTokenProvider.class);
		when(tokenProvider.accessToken()).thenReturn("access-token");
		KisMarketDataGateway gateway = new KisMarketDataGateway(
			builder.baseUrl("https://example.test").build(),
			properties,
			tokenProvider,
			new KisCircuitBreaker()
		);

		server.expect(queryParam("FID_INPUT_ISCD", "005930")).andRespond(withServerError());
		server.expect(queryParam("FID_INPUT_ISCD", "005930")).andRespond(withSuccess("""
			{
			  "rt_cd": "0",
			  "output": {"stck_prpr": "78000", "prdy_vrss": "0", "prdy_ctrt": "0"}
			}
			""", MediaType.APPLICATION_JSON));

		assertThat(gateway.fetchQuote("005930")).isPresent();
		server.verify();
	}

	@Test
	void doesNotRetryNonTransientClientErrors() {
		KisMarketProperties properties = configuredProperties();
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisAccessTokenProvider tokenProvider = mock(KisAccessTokenProvider.class);
		when(tokenProvider.accessToken()).thenReturn("access-token");
		KisMarketDataGateway gateway = new KisMarketDataGateway(
			builder.baseUrl("https://example.test").build(),
			properties,
			tokenProvider,
			new KisCircuitBreaker()
		);

		server.expect(queryParam("FID_INPUT_ISCD", "005930")).andRespond(withBadRequest());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.fetchQuote("005930"))
			.isInstanceOf(RestClientResponseException.class);
		server.verify();
	}

	@Test
	void mapsOfficialDailyPriceAndWholeMarketForeignFlowContracts() {
		KisMarketProperties properties = configuredProperties();
		properties.setCollectionDelay(Duration.ZERO);
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisAccessTokenProvider tokenProvider = mock(KisAccessTokenProvider.class);
		when(tokenProvider.accessToken()).thenReturn("access-token");
		KisMarketDataGateway gateway = new KisMarketDataGateway(
			builder.baseUrl("https://example.test").build(),
			properties,
			tokenProvider,
			new KisCircuitBreaker()
		);

		server.expect(header("tr_id", "FHKST03010100"))
			.andExpect(queryParam("FID_INPUT_ISCD", "005930"))
			.andExpect(queryParam("FID_INPUT_DATE_1", "20260801"))
			.andExpect(queryParam("FID_INPUT_DATE_2", "20260831"))
			.andRespond(withSuccess("""
				{"rt_cd":"0","output2":[
				  {"stck_bsop_date":"20260801","stck_oprc":"70000","stck_hgpr":"71000",
				   "stck_lwpr":"69000","stck_clpr":"70500","acml_vol":"123456"}
				]}
				""", MediaType.APPLICATION_JSON));
		server.expect(header("tr_id", "FHPTJ04040000"))
			.andExpect(queryParam("FID_INPUT_ISCD_1", "KSP"))
			.andRespond(withSuccess("""
				{"rt_cd":"0","output":[
				  {"stck_bsop_date":"20260831","frgn_ntby_tr_pbmn":"1250"}
				]}
				""", MediaType.APPLICATION_JSON));
		server.expect(header("tr_id", "FHPTJ04040000"))
			.andExpect(queryParam("FID_INPUT_ISCD_1", "KSQ"))
			.andRespond(withSuccess("""
				{"rt_cd":"0","output":[
				  {"stck_bsop_date":"20260831","frgn_ntby_tr_pbmn":"-250"}
				]}
				""", MediaType.APPLICATION_JSON));

		var prices = gateway.fetchDailyPrices(
			"005930", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
		);
		assertThat(prices).singleElement().satisfies(price -> {
			assertThat(price.closePriceKrw()).isEqualByComparingTo("70500");
			assertThat(price.volume()).isEqualTo(123456L);
		});
		var flows = gateway.fetchForeignNetFlows(LocalDate.of(2026, 8, 31));
		assertThat(flows).extracting(flow -> flow.netPurchaseAmountKrw())
			.containsExactly(new BigDecimal("1250000000"), new BigDecimal("-250000000"));
		server.verify();
	}

	private static KisMarketProperties configuredProperties() {
		KisMarketProperties properties = new KisMarketProperties();
		properties.setEnabled(true);
		properties.setAppKey("app-key");
		properties.setAppSecret("app-secret");
		return properties;
	}
}
