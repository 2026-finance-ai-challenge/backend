package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureInsightGateway;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightEvidence;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureInsightGeneration;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiDisclosureInsightClient implements DisclosureInsightGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiDisclosureInsightClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public DisclosureInsightGeneration summarize(
		String receiptNumber,
		String title,
		List<DisclosureInsightEvidence> evidence
	) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			Response response = restClient.post()
				.uri("/internal/v1/disclosures/summaries")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new Request(
					receiptNumber,
					title,
					evidence.stream().map(Evidence::from).toList()
				))
				.retrieve()
				.body(Response.class);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response.toDomain();
		}
		catch (RestClientException exception) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(String receiptNumber, String title, List<Evidence> evidence) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Evidence(String id, String heading, String content) {
		private static Evidence from(DisclosureInsightEvidence evidence) {
			return new Evidence(evidence.id(), evidence.heading(), evidence.content());
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		String what,
		String why,
		String impact,
		List<String> evidenceIds,
		boolean sufficientEvidence,
		String refusalReason,
		String model,
		String promptVersion
	) {
		private DisclosureInsightGeneration toDomain() {
			return new DisclosureInsightGeneration(
				what,
				why,
				impact,
				evidenceIds,
				sufficientEvidence,
				refusalReason,
				model,
				promptVersion
			);
		}
	}
}
