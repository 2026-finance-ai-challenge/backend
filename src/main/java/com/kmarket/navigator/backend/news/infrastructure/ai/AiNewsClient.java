package com.kmarket.navigator.backend.news.infrastructure.ai;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;
import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsAnalysis;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;
import com.kmarket.navigator.backend.news.domain.TermExplanation;
import com.kmarket.navigator.backend.news.domain.TermReference;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Component
class AiNewsClient implements NewsAiGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;

	AiNewsClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties
	) {
		this.restClient = restClient;
		this.properties = properties;
	}

	@Override
	public NewsAnalysis analyze(
		String title,
		List<String> paragraphs,
		List<String> candidateCompanies
	) {
		SignalResponse signals = post(
			"/internal/v1/news/signals",
			new AnalysisRequest(title, paragraphs, candidateCompanies),
			SignalResponse.class
		);
		String sourceHash = sha256(title);
		String id = sourceHash;
		TitleBatchResponse titles = post(
			"/internal/v1/translations/titles",
			new TitleBatchRequest(
				List.of(new TitleSource(id, sourceHash, title)),
				"en",
				"news-title-v1"
			),
			TitleBatchResponse.class
		);
		if (titles.items().size() != 1
			|| !id.equals(titles.items().getFirst().id())
			|| !sourceHash.equals(titles.items().getFirst().sourceHash())) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		return signals.toDomain(
			titles.items().getFirst().translatedText(),
			titles.promptVersion()
		);
	}

	@Override
	public TermExplanation explainTerm(
		String selectedText,
		String articleContext,
		List<TermReference> evidence,
		String safetyIdentifier
	) {
		TermResponse response = post(
			"/internal/v1/news/terms/explanations",
			new TermRequest(
				selectedText,
				articleContext,
				evidence.stream().map(TermEvidence::from).toList(),
				safetyIdentifier
			),
			TermResponse.class
		);
		return response.toDomain(selectedText);
	}

	private <T> T post(String path, Object body, Class<T> responseType) {
		if (properties.serviceToken() == null || properties.serviceToken().isBlank()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		try {
			T response = restClient.post()
				.uri(path)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceToken())
				.body(body)
				.retrieve()
				.body(responseType);
			if (response == null) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return response;
		} catch (RestClientException exception) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record AnalysisRequest(
		String title,
		List<String> paragraphs,
		List<String> candidateCompanies
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record SignalResponse(
		String eventType,
		NewsSentiment sentiment,
		NewsImportance importance,
		MarketImpact marketImpact,
		NewsImportance marketImpactImportance,
		BigDecimal marketImpactScore,
		BigDecimal eventConfidence,
		BigDecimal sentimentConfidence,
		BigDecimal importanceConfidence,
		BigDecimal marketImpactConfidence,
		String model
	) {
		private NewsAnalysis toDomain(String englishTitle, String titlePromptVersion) {
			return new NewsAnalysis(
				englishTitle,
				List.of(),
				null,
				null,
				null,
				eventType,
				sentiment,
				importance,
				marketImpact,
				marketImpactImportance,
				marketImpactScore,
				eventConfidence,
				sentimentConfidence,
				importanceConfidence,
				marketImpactConfidence,
				model,
				titlePromptVersion
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TitleBatchRequest(
		List<TitleSource> items,
		String targetLocale,
		String translationVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TitleSource(String id, String sourceHash, String sourceText) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TitleBatchResponse(
		List<TranslatedTitle> items,
		String targetLocale,
		String translationVersion,
		String model,
		String promptVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TranslatedTitle(String id, String sourceHash, String translatedText) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TermRequest(
		String selectedText,
		String articleContext,
		List<TermEvidence> evidence,
		String safetyIdentifier
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TermEvidence(String id, String title, String content, String sourceUrl) {
		private static TermEvidence from(TermReference reference) {
			return new TermEvidence(
				reference.id(),
				reference.title(),
				reference.content(),
				reference.sourceUrl()
			);
		}
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record TermResponse(
		String normalizedTerm,
		String definition,
		String contextualMeaning,
		List<String> evidenceIds,
		BigDecimal confidence,
		boolean reviewRequired,
		boolean sufficientEvidence,
		String refusalReason,
		String model,
		String promptVersion
	) {
		private TermExplanation toDomain(String selectedText) {
			return new TermExplanation(
				selectedText,
				normalizedTerm,
				definition,
				contextualMeaning,
				evidenceIds.stream()
					.map(id -> new TermReference(id, "", "", "", null))
					.toList(),
				confidence,
				reviewRequired,
				sufficientEvidence,
				refusalReason,
				model,
				promptVersion
			);
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
			);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
		}
	}
}
