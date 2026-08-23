package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightEvidence;

class AiDisclosureInsightClientTests {

	@Test
	void sendsOnlyBoundedEvidenceThroughAuthenticatedSnakeCaseContract() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiDisclosureInsightClient client = new AiDisclosureInsightClient(
			builder.build(),
			properties
		);

		server.expect(requestTo(containsString("/internal/v1/disclosures/summaries")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "receipt_number": "20260823800002",
				  "title": "Major Business Report",
				  "evidence": [{
				    "id": "S1",
				    "heading": "Investment",
				    "content": "The company approved a facility."
				  }]
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "what": "A facility was approved.",
				  "why": "The filing states an expansion purpose.",
				  "impact": "Capacity may increase.",
				  "evidence_ids": ["S1"],
				  "sufficient_evidence": true,
				  "refusal_reason": null,
				  "model": "gpt-5-mini",
				  "prompt_version": "filing-summary-v1"
				}
				""", MediaType.APPLICATION_JSON));

		var result = client.summarize(
			"20260823800002",
			"Major Business Report",
			List.of(new DisclosureInsightEvidence(
				"S1",
				"Investment",
				"The company approved a facility."
			))
		);

		assertThat(result.what()).isEqualTo("A facility was approved.");
		assertThat(result.evidenceIds()).containsExactly("S1");
		server.verify();
	}
}
