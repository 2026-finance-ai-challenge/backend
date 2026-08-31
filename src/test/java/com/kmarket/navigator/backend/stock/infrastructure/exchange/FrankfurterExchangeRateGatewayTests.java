package com.kmarket.navigator.backend.stock.infrastructure.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FrankfurterExchangeRateGatewayTests {

	@Test
	void mapsOfficialV2UsdKrwResponse() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		FrankfurterProperties properties = new FrankfurterProperties();
		FrankfurterExchangeRateGateway gateway = new FrankfurterExchangeRateGateway(
			builder.baseUrl("https://api.frankfurter.dev").build(), properties
		);
		server.expect(requestTo("https://api.frankfurter.dev/v2/rate/USD/KRW"))
			.andRespond(withSuccess("""
				{"date":"2026-08-31","base":"USD","quote":"KRW","rate":1375.26}
				""", MediaType.APPLICATION_JSON));

		var snapshot = gateway.fetchUsdKrw().orElseThrow();
		assertThat(snapshot.krwPerUnit()).isEqualByComparingTo("1375.26");
		assertThat(snapshot.source()).isEqualTo("FRANKFURTER_V2");
		server.verify();
	}
}
