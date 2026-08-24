package com.kmarket.navigator.backend.translation.domain;

import java.util.UUID;

public record GeneratedTitle(
	UUID id,
	String sourceHash,
	String translatedText,
	String targetLocale,
	String translationVersion,
	String modelId,
	String promptVersion
) {
}
