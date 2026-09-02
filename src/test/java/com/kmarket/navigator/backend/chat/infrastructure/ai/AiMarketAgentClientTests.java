package com.kmarket.navigator.backend.chat.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.chat.domain.ChatContextType;
import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;

class AiMarketAgentClientTests {
	@Test
	void invalidOutputIsNotReportedAsARetryableOutage() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiMarketAgentClient client = new AiMarketAgentClient(builder.build(), properties);
		server.expect(requestTo(containsString("/internal/v1/agent/answers")))
			.andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
				.contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"AI_INVALID_OUTPUT\",\"message\":\"invalid\",\"request_id\":\"test\"}"));
		assertThatThrownBy(() -> client.answer(new ChatContext(ChatContextType.GENERAL, null, null, "Market"),
			"Explain", List.of(), List.of(), "a".repeat(64), "auto"))
			.isInstanceOfSatisfying(com.kmarket.navigator.backend.global.error.BusinessException.class,
				exception -> assertThat(exception.errorCode()).isEqualTo(com.kmarket.navigator.backend.global.error.ErrorCode.AI_INVALID_OUTPUT));
		server.verify();
	}

	@Test
	void sendsAuthenticatedEvidenceAndHashedSafetyIdentifier() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiMarketAgentClient client = new AiMarketAgentClient(builder.build(), properties);

		server.expect(requestTo(containsString("/internal/v1/agent/answers")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "context_type": "STOCK",
				  "context_title": "Samsung Electronics",
				  "question": "What is the price?",
				  "history": [],
				  "evidence": [{
				    "id": "E1",
				    "title": "Observed quote",
				    "content": "KRW 78000",
				    "source": "KIS",
				    "as_of": "2026-08-23T01:00:00Z"
				  }],
				  "safety_identifier": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				  "answer_locale": "ko"
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "answer": "The observed price is KRW 78,000. [E1]",
				  "evidence_ids": ["E1"],
				  "insufficient_evidence": false,
				  "refusal_reason": null,
				  "suggested_room_name": "Samsung quote",
				  "disclaimer": "For information only.",
				  "confidence": 0.95,
				  "model": "gpt-5-mini",
				  "prompt_version": "market-agent-v1"
				}
				""", MediaType.APPLICATION_JSON));

		var result = client.answer(
			new ChatContext(ChatContextType.STOCK, "005930", null, "Samsung Electronics"),
			"What is the price?",
			List.of(),
			List.of(new AgentEvidence(
				"E1",
				"Observed quote",
				"KRW 78000",
				"KIS",
				Instant.parse("2026-08-23T01:00:00Z"),
				"005930",
				null
			)),
			"a".repeat(64), "ko"
		);

		assertThat(result.answer()).contains("KRW 78,000");
		assertThat(result.evidenceIds()).containsExactly("E1");
		server.verify();
	}
}
