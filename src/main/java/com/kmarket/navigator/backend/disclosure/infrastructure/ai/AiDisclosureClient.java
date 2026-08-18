package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiDisclosureClient implements DisclosureRagGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiDisclosureClient(RestClient aiServiceRestClient, AiServiceProperties properties) {
		this.restClient = aiServiceRestClient;
		this.properties = properties;
	}

	@Override
	public DisclosureAnswer ask(String receiptNumber, DisclosureQuestion question) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			Response response = restClient.post()
				.uri("/internal/v1/disclosures/{receiptNumber}/questions", receiptNumber)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(Request.from(question))
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
	private record Request(String question, SelectedContext selectedContext) {
		private static Request from(DisclosureQuestion question) {
			return new Request(
				question.question(),
				question.selectedContext() == null
					? null
					: new SelectedContext(
						question.selectedContext().sectionId(),
						question.selectedContext().text()
					)
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record SelectedContext(UUID sectionId, String text) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		String answer,
		boolean refused,
		String refusalReason,
		List<Citation> citations,
		String model,
		String promptVersion
	) {
		private DisclosureAnswer toDomain() {
			return new DisclosureAnswer(
				answer,
				refused,
				refusalReason,
				citations.stream().map(Citation::toDomain).toList(),
				model,
				promptVersion
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Citation(
		String id,
		UUID chunkId,
		UUID documentId,
		int documentVersion,
		List<UUID> sectionIds,
		int firstOrdinal,
		int lastOrdinal,
		String heading,
		String excerpt
	) {
		private DisclosureAnswer.Citation toDomain() {
			return new DisclosureAnswer.Citation(
				id,
				chunkId,
				documentId,
				documentVersion,
				sectionIds,
				firstOrdinal,
				lastOrdinal,
				heading,
				excerpt
			);
		}
	}
}
