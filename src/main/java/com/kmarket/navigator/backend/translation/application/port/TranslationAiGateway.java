package com.kmarket.navigator.backend.translation.application.port;

import java.util.List;
import java.util.function.Consumer;

import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

public interface TranslationAiGateway {

	default GeneratedTranslation streamNews(
		String sourceHash, String title, List<String> paragraphs, String contentAvailability,
		String version, tools.jackson.databind.JsonNode cachedSummaries, Consumer<GeneratedTranslation> progress
	) {
		return streamNews(sourceHash, title, paragraphs, contentAvailability, version, progress);
	}

	default GeneratedTranslation streamNews(
		String sourceHash, String title, List<String> paragraphs, String contentAvailability,
		String version, Consumer<GeneratedTranslation> progress
	) {
		return translateNews(sourceHash, title, paragraphs, contentAvailability, "en", version);
	}

	List<GeneratedTitle> translateTitles(List<TitleTranslationJob> jobs);

	GeneratedTranslation translateNews(
		String sourceHash,
		String title,
		List<String> paragraphs,
		String contentAvailability,
		String targetLocale,
		String version
	);

	GeneratedTranslation translateDisclosureSection(
		String receiptNumber,
		int documentVersion,
		String sectionId,
		String sourceHash,
		String heading,
		String text,
		String tableDataJson,
		String version
	);
}
