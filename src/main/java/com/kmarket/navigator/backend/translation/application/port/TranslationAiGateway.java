package com.kmarket.navigator.backend.translation.application.port;

import java.util.List;

import com.kmarket.navigator.backend.translation.domain.GeneratedTranslation;

public interface TranslationAiGateway {

	GeneratedTranslation translateNews(
		String sourceHash,
		String title,
		List<String> paragraphs,
		String contentAvailability,
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
