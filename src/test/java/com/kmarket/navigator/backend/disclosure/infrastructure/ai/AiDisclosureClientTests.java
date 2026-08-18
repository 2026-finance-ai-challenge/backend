package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;

class AiDisclosureClientTests {

	@Test
	void usesSnakeCaseInternalContract() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiDisclosureClient client = new AiDisclosureClient(builder.build(), properties);
		UUID sectionId = UUID.randomUUID();

		server.expect(requestTo(containsString("/internal/v1/disclosures/20260818000305/questions")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "question": "What changed?",
				  "selected_context": {
				    "section_id": "%s",
				    "text": "Selected filing text"
				  }
				}
				""".formatted(sectionId)))
			.andRespond(withSuccess("""
				{
				  "answer": "Revenue increased. [C1]",
				  "refused": false,
				  "refusal_reason": null,
				  "citations": [],
				  "model": "gpt-5-mini",
				  "prompt_version": "filing-rag-v1"
				}
				""", MediaType.APPLICATION_JSON));

		var answer = client.ask(
			"20260818000305",
			new DisclosureQuestion(
				"What changed?",
				new DisclosureQuestion.SelectedContext(sectionId, "Selected filing text")
			)
		);

		assertThat(answer.answer()).isEqualTo("Revenue increased. [C1]");
		assertThat(answer.promptVersion()).isEqualTo("filing-rag-v1");
		server.verify();
	}
}
