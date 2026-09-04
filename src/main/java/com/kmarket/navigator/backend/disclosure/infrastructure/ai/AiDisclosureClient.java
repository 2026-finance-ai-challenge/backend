package com.kmarket.navigator.backend.disclosure.infrastructure.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiDisclosureClient implements DisclosureRagGateway {
	private static final Logger log = LoggerFactory.getLogger(AiDisclosureClient.class);

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
		catch (RestClientResponseException exception) {
			log.warn("Filing AI request failed receipt={} status={}", receiptNumber, exception.getStatusCode().value());
			if (exception.getStatusCode().value() == 400 || exception.getStatusCode().value() == 422) {
				throw new BusinessException(ErrorCode.INVALID_CHAT_MESSAGE);
			}
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		catch (RestClientException exception) {
			log.warn("Filing AI transport failed receipt={} type={}", receiptNumber, exception.getClass().getSimpleName());
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public List<com.kmarket.navigator.backend.disclosure.domain.FilingEvidence> retrieve(
		List<String> stockCodes, String question, java.time.LocalDate from, java.time.LocalDate to, boolean financials) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		try {
			var response = restClient.post().uri("/internal/v1/disclosures/evidence")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new EvidenceRequest(stockCodes, question, from, to, financials)).retrieve().body(EvidenceResponse[].class);
			if (response == null) throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			return java.util.Arrays.stream(response).map(r -> new com.kmarket.navigator.backend.disclosure.domain.FilingEvidence(
				r.receiptNumber(), r.stockCode(), r.title(), r.filedDate(), r.detectedAt(), r.content(), r.sectionIds(), r.retrievalMethod())).toList();
		} catch (RestClientException exception) {
			log.warn("Filing evidence retrieval failed type={}", exception.getClass().getSimpleName());
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record EvidenceRequest(List<String> stockCodes, String question, java.time.LocalDate fromDate,
		java.time.LocalDate toDate, boolean financials) { }

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record EvidenceResponse(String receiptNumber, String stockCode, String title, java.time.LocalDate filedDate,
		java.time.Instant detectedAt, String content, List<UUID> sectionIds, String retrievalMethod) { }

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(String question, SelectedContext selectedContext, String answerLocale) {
		private static Request from(DisclosureQuestion question) {
			return new Request(
				question.question(),
				question.selectedContext() == null
					? null
					: new SelectedContext(
						question.selectedContext().sectionId(),
						question.selectedContext().text(),
						question.selectedContext().translationSourceHash()
					),
				question.answerLocale()
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record SelectedContext(UUID sectionId, String text, String translationSourceHash) {
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
