package com.kmarket.navigator.backend.chat.infrastructure.ai;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.chat.application.port.AgentGateway;
import com.kmarket.navigator.backend.chat.domain.AgentAnswer;
import com.kmarket.navigator.backend.chat.domain.AgentEvidence;
import com.kmarket.navigator.backend.chat.domain.AgentHistoryMessage;
import com.kmarket.navigator.backend.chat.domain.ChatContext;
import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiMarketAgentClient implements AgentGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiMarketAgentClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public AgentAnswer answer(
		ChatContext context,
		String question,
		List<AgentHistoryMessage> history,
		List<AgentEvidence> evidence,
		String safetyIdentifier
	) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			Response response = restClient.post()
				.uri("/internal/v1/agent/answers")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(new Request(
					context.type().name(),
					context.title(),
					question,
					history.stream().map(History::from).toList(),
					evidence.stream().map(Evidence::from).toList(),
					safetyIdentifier
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
	private record Request(
		String contextType,
		String contextTitle,
		String question,
		List<History> history,
		List<Evidence> evidence,
		String safetyIdentifier
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record History(String role, String content) {
		private static History from(AgentHistoryMessage message) {
			return new History(message.role().name(), message.content());
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Evidence(
		String id,
		String title,
		String content,
		String source,
		String asOf
	) {
		private static Evidence from(AgentEvidence evidence) {
			return new Evidence(
				evidence.id(),
				evidence.title(),
				evidence.content(),
				evidence.source(),
				evidence.asOf() == null ? null : evidence.asOf().toString()
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Response(
		String answer,
		List<String> evidenceIds,
		boolean insufficientEvidence,
		String refusalReason,
		String suggestedRoomName,
		String disclaimer,
		BigDecimal confidence,
		String model,
		String promptVersion
	) {
		private AgentAnswer toDomain() {
			return new AgentAnswer(
				answer,
				evidenceIds,
				insufficientEvidence,
				refusalReason,
				suggestedRoomName,
				disclaimer,
				confidence,
				model,
				promptVersion
			);
		}
	}
}
