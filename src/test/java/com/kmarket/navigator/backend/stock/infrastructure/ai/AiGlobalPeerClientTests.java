package com.kmarket.navigator.backend.stock.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;

class AiGlobalPeerClientTests {

	@Test
	void authenticatesInternalRequestAndMapsGroundedPeerContract() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiGlobalPeerClient client = new AiGlobalPeerClient(builder.build(), properties);

		server.expect(requestTo(containsString("/internal/v1/peers/005930")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{"safety_identifier":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
				"""))
			.andRespond(withSuccess(responseBody(), MediaType.APPLICATION_JSON));

		var analysis = client.analyze(
			"005930",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		);

		assertThat(analysis.primaryPeer().ticker()).isEqualTo("INTC");
		assertThat(analysis.peers()).hasSize(3);
		assertThat(analysis.comparisons()).extracting("dimension")
			.containsExactly("overall_business", "semiconductor", "memory");
		assertThat(analysis.keyStrengths()).hasSize(4);
		assertThat(analysis.financialDataAsOf()).hasToString("2025-12-31");
		server.verify();
	}

	private static String responseBody() {
		return """
			{
			  "stock_code": "005930",
			  "stock_name": "삼성전자",
			  "stock_name_en": "Samsung Electronics",
			  "market": "KOSPI",
			  "target_sector": "Information Technology",
			  "target_industry": "Semiconductors",
			  "target_business_model": "Integrated electronics and semiconductor manufacturing",
			  "headline": "Samsung Electronics and its closest global peers",
			  "summary": "A grounded comparison based on validated ranker output.",
			  "primary_peer": %s,
			  "peers": [%s, %s, %s],
			  "comparisons": [
			    {"dimension":"overall_business","description":"Overall reference.","peer":%s},
			    {"dimension":"semiconductor","description":"Foundry reference.","peer":%s},
			    {"dimension":"memory","description":"Memory reference.","peer":%s}
			  ],
			  "key_strengths": [
			    {"title":"AI Technology","description":"AI capability.","icon_key":"ai"},
			    {"title":"Consumer Devices","description":"Device reach.","icon_key":"devices"},
			    {"title":"Foundry Capability","description":"Foundry scale.","icon_key":"foundry"},
			    {"title":"Memory Technology","description":"Memory portfolio.","icon_key":"memory"}
			  ],
			  "confidence_score": 0.5201,
			  "confidence_level": "MEDIUM",
			  "financial_data_as_of": "2025-12-31",
			  "ranker_model_version": "global-peer-ranker-test-v1",
			  "narrative_model": "gpt-5-mini",
			  "prompt_version": "global-peer-narrative-v1",
			  "source": "HANA_VALIDATED_CATALOG"
			}
			""".formatted(peer(1, "overall_business", "INTC", "Intel"),
			peer(1, "overall_business", "INTC", "Intel"),
			peer(2, "semiconductor", "TSM", "Taiwan Semiconductor"),
			peer(3, "memory", "MU", "Micron Technology"),
			peer(1, "overall_business", "INTC", "Intel"),
			peer(2, "semiconductor", "TSM", "Taiwan Semiconductor"),
			peer(3, "memory", "MU", "Micron Technology"));
	}

	private static String peer(int rank, String dimension, String ticker, String companyName) {
		return """
			{
			  "dimension":"%s","rank":%d,"ticker":"%s","company_name":"%s",
			  "exchange":"NASDAQ","country":"US","similarity_score":0.52,
			  "business_tags":["semiconductors"],"sector":"Information Technology",
			  "industry":"Semiconductors","business_model":"Semiconductor manufacturing",
			  "scale_bucket":"MEGA_CAP","fiscal_year":2025,"market_cap_usd":658355740000,
			  "revenue_usd":52853000000,"operating_income_usd":1000000000,
			  "net_income_usd":500000000,"financial_data_source":"SEC_COMPANYFACTS",
			  "financial_similarity_score":0.776
			}
			""".formatted(dimension, rank, ticker, companyName);
	}
}
