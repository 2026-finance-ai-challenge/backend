package com.kmarket.navigator.backend.news.infrastructure.ai;

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

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.TermReference;

class AiNewsClientTests {

	@Test
	void usesAuthenticatedSnakeCaseContractsForAnalysisAndTermExplanation() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AiServiceProperties properties = new AiServiceProperties();
		properties.setServiceToken("test-service-token");
		AiNewsClient client = new AiNewsClient(builder.build(), properties);

		server.expect(requestTo(containsString("/internal/v1/news/signals")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "title": "삼성전자 투자 확대",
				  "paragraphs": ["반도체 설비 투자를 확대한다."],
				  "candidate_companies": ["삼성전자"]
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "event_type": "CAPEX",
				  "sentiment": "POSITIVE",
				  "importance": "HIGH",
				  "market_impact": "POSITIVE",
				  "market_impact_importance": "MEDIUM",
				  "market_impact_score": 0.55,
				  "event_confidence": 0.9,
				  "sentiment_confidence": 0.8,
				  "importance_confidence": 0.85,
				  "market_impact_confidence": 0.75,
				  "model": "kmarket-finance-transformer-v1"
				}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(containsString("/internal/v1/translations/titles")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "items": [{
				    "id": "4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
				    "source_hash": "4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
				    "source_text": "삼성전자 투자 확대"
				  }],
				  "target_locale": "en",
				  "translation_version": "news-title-v1"
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "items": [{
				    "id": "4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
				    "source_hash": "4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
				    "translated_text": "Samsung Electronics expands investment"
				  }],
				  "target_locale": "en",
				  "translation_version": "news-title-v1",
				  "model": "gpt-5-mini",
				  "prompt_version": "news-title-v1"
				}
				""", MediaType.APPLICATION_JSON));

		var analysis = client.analyze(
			"삼성전자 투자 확대",
			List.of("반도체 설비 투자를 확대한다."),
			List.of("삼성전자")
		);
		assertThat(analysis.englishTitle()).isEqualTo("Samsung Electronics expands investment");
		assertThat(analysis.marketImpactImportance()).isEqualTo(NewsImportance.MEDIUM);
		assertThat(analysis.marketImpactScore()).isEqualByComparingTo("0.55");
		assertThat(analysis.promptVersion()).isEqualTo("news-title-v1");
		server.verify();
		server.reset();

		server.expect(requestTo(containsString("/internal/v1/news/terms/explanations")))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-service-token"))
			.andExpect(content().json("""
				{
				  "selected_text": "rights offering",
				  "article_context": "The rights offering funds expansion.",
				  "evidence": [{
				    "id": "A1",
				    "title": "Article context",
				    "content": "The rights offering funds expansion.",
				    "source_url": "https://news.example.com/1"
				  }],
				  "safety_identifier": "hashed-client"
				}
				"""))
			.andRespond(withSuccess("""
				{
				  "normalized_term": "rights offering",
				  "definition": "An offer of newly issued shares to eligible holders.",
				  "contextual_meaning": "The company is raising expansion funds.",
				  "evidence_ids": ["A1"],
				  "confidence": 0.91,
				  "review_required": false,
				  "sufficient_evidence": true,
				  "refusal_reason": null,
				  "model": "gpt-5-mini",
				  "prompt_version": "news-term-v1"
				}
				""", MediaType.APPLICATION_JSON));

		var explanation = client.explainTerm(
			"rights offering",
			"The rights offering funds expansion.",
			List.of(new TermReference(
				"A1",
				"Article context",
				"The rights offering funds expansion.",
				"Original article",
				"https://news.example.com/1"
			)),
			"hashed-client"
		);
		assertThat(explanation.sources()).extracting(TermReference::id).containsExactly("A1");
		assertThat(explanation.sufficientEvidence()).isTrue();
		server.verify();
	}
}
