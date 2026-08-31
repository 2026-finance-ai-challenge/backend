package com.kmarket.navigator.backend.translation.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.kmarket.navigator.backend.disclosure.infrastructure.ai.AiServiceProperties;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.translation.application.TranslationProviderException;
import com.kmarket.navigator.backend.translation.application.TranslationProviderException.Failure;
import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.node.ObjectNode;

@Component
class AiTranslationClient implements TranslationAiGateway {

	private final RestClient restClient;
	private final AiServiceProperties properties;
	private final ObjectMapper objectMapper;

	AiTranslationClient(
		@Qualifier("aiServiceRestClient") RestClient restClient,
		AiServiceProperties properties,
		ObjectMapper objectMapper
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<GeneratedTitle> translateTitles(List<TitleTranslationJob> jobs) {
		if (jobs.isEmpty()) {
			return List.of();
		}
		String version = jobs.getFirst().translationVersion();
		if (jobs.stream().anyMatch(job -> !version.equals(job.translationVersion()))) {
			throw new IllegalArgumentException("Title translation versions must match");
		}
		TitleBatchResponse response = post(
			"/internal/v1/translations/titles",
			new TitleBatchRequest(
				jobs.stream().map(job -> new TitleSource(
					job.id().toString(), job.sourceHash(), job.sourceText()
				)).toList(),
				"en",
				version
			),
			TitleBatchResponse.class
		);
		Map<UUID, TitleTranslationJob> expected = jobs.stream().collect(Collectors.toMap(
			TitleTranslationJob::id,
			Function.identity()
		));
		if (!"en".equals(response.targetLocale()) || !version.equals(response.translationVersion())
			|| response.items().size() != expected.size()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		java.util.Set<UUID> seen = new java.util.HashSet<>();
		List<GeneratedTitle> generated = response.items().stream().map(item -> {
			UUID id;
			try {
				id = UUID.fromString(item.id());
			}
			catch (IllegalArgumentException exception) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			TitleTranslationJob source = expected.get(id);
			if (source == null || !seen.add(id) || !source.sourceHash().equals(item.sourceHash())) {
				throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
			}
			return new GeneratedTitle(
				id, item.sourceHash(), item.translatedText(), response.targetLocale(),
				response.translationVersion(), response.model(), response.promptVersion()
			);
		}).toList();
		if (seen.size() != expected.size()) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		return generated;
	}

	@Override
	public GeneratedTranslation translateNews(
		String sourceHash,
		String title,
		List<String> paragraphs,
		String contentAvailability,
		String version
	) {
		NewsResponse response = post(
			"/internal/v1/news/narratives",
			new NewsRequest(sourceHash, title, paragraphs, contentAvailability, "en", version),
			NewsResponse.class
		);
		ObjectNode result = objectMapper.createObjectNode();
		var translated = result.putArray("translatedParagraphs");
		response.translatedParagraphs().forEach(translated::add);
		result.put("what", response.what());
		result.put("why", response.why());
		result.put("impact", response.impact());
		result.put("contentAvailability", response.contentAvailability());
		return generated(
			sourceHash, version, response.sourceHash(), response.targetLocale(),
			response.translationVersion(), result, response.model(), response.promptVersion()
		);
	}

	@Override
	public GeneratedTranslation translateDisclosureSection(
		String receiptNumber,
		int documentVersion,
		String sectionId,
		String sourceHash,
		String heading,
		String text,
		String tableDataJson,
		String version
	) {
		SectionResponse response = post(
			"/internal/v1/disclosures/section-translations",
			new SectionRequest(
				receiptNumber, documentVersion, sectionId, sourceHash, heading, text,
				tableDataJson, "en", version
			),
			SectionResponse.class
		);
		if (!receiptNumber.equals(response.receiptNumber())
			|| documentVersion != response.documentVersion()
			|| !sectionId.equals(response.sectionId())) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		ObjectNode result = objectMapper.createObjectNode();
		putNullable(result, "translatedHeading", response.translatedHeading());
		putNullable(result, "translatedText", response.translatedText());
		if (response.translatedTableDataJson() == null) {
			result.putNull("translatedTableData");
		}
		else {
			result.set("translatedTableData", objectMapper.readTree(response.translatedTableDataJson()));
		}
		return generated(
			sourceHash, version, response.sourceHash(), response.targetLocale(),
			response.translationVersion(), result, response.model(), response.promptVersion()
		);
	}

	private GeneratedTranslation generated(
		String expectedHash,
		String expectedVersion,
		String actualHash,
		String locale,
		String actualVersion,
		ObjectNode result,
		String model,
		String promptVersion
	) {
		if (!expectedHash.equals(actualHash) || !expectedVersion.equals(actualVersion)
			|| !"en".equals(locale)) {
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
		return new GeneratedTranslation(
			actualHash, locale, actualVersion, result, model, promptVersion
		);
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
		}
		catch (RestClientResponseException exception) {
			throw providerException(exception);
		}
		catch (RestClientException exception) {
			throw new TranslationProviderException(Failure.UNAVAILABLE);
		}
	}

	private TranslationProviderException providerException(RestClientResponseException exception) {
		String code;
		try {
			code = objectMapper.readTree(exception.getResponseBodyAsString()).path("code").asString();
		}
		catch (RuntimeException ignored) {
			code = "";
		}
		Failure failure = switch (code) {
			case "AI_PROVIDER_QUOTA_EXHAUSTED" -> Failure.QUOTA_EXHAUSTED;
			case "AI_PROVIDER_RATE_LIMITED" -> Failure.RATE_LIMITED;
			case "AI_PROVIDER_TIMEOUT" -> Failure.TIMEOUT;
			default -> Failure.UNAVAILABLE;
		};
		return new TranslationProviderException(failure);
	}

	private static void putNullable(ObjectNode node, String field, String value) {
		if (value == null) {
			node.putNull(field);
		}
		else {
			node.put(field, value);
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
	private record NewsRequest(
		String sourceHash, String title, List<String> paragraphs, String contentAvailability,
		String targetLocale, String translationVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record NewsResponse(
		String sourceHash, List<String> translatedParagraphs, String what, String why,
		String impact, String contentAvailability, String targetLocale,
		String translationVersion, String model, String promptVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record SectionRequest(
		String receiptNumber, int documentVersion, String sectionId, String sourceHash,
		String heading, String text, String tableDataJson, String targetLocale,
		String translationVersion
	) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record SectionResponse(
		String receiptNumber, int documentVersion, String sectionId, String sourceHash,
		String translatedHeading, String translatedText, String translatedTableDataJson,
		String targetLocale, String translationVersion, String model, String promptVersion
	) {
	}
}
