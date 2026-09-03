package com.kmarket.navigator.backend.chat.infrastructure.ai;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	private static final Logger log = LoggerFactory.getLogger(AiMarketAgentClient.class);

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
		String safetyIdentifier,
		String answerLocale
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
					safetyIdentifier,
					answerLocale
				))
				.retrieve()
				.body(Response.class);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response.toDomain();
		}
		catch (RestClientResponseException exception) {
			String code = failureCode(exception);
			String requestId = exception.getResponseHeaders() == null ? null
				: exception.getResponseHeaders().getFirst("x-request-id");
			log.warn("시장 Agent 요청 실패 generation={} status={} code={} aiRequestId={}",
				org.slf4j.MDC.get("chatGenerationId"), exception.getStatusCode().value(), code,
				requestId != null && requestId.matches("[a-f0-9-]{36}") ? requestId : "unknown");
			throw new BusinessException("AI_INVALID_OUTPUT".equals(code)
				? ErrorCode.AI_INVALID_OUTPUT : ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		catch (RestClientException exception) {
			log.warn("시장 Agent 연결 실패 generation={} type={} cause={}",
				org.slf4j.MDC.get("chatGenerationId"), exception.getClass().getSimpleName(),
				exception.getMostSpecificCause().getClass().getSimpleName());
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	private static String failureCode(RestClientResponseException exception) {
		try {
			var body = exception.getResponseBodyAs(AiFailure.class);
			String code = body == null ? null : body.code();
			return code != null && code.matches("[A-Z_]{1,64}") ? code : "UNKNOWN";
		} catch (RuntimeException ignored) {
			return "UNKNOWN";
		}
	}

	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
	private record AiFailure(String code) { }

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record Request(
		String contextType,
		String contextTitle,
		String question,
		List<History> history,
		List<Evidence> evidence,
		String safetyIdentifier,
		String answerLocale
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
