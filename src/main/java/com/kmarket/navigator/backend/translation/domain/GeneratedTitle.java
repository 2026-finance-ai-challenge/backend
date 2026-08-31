package com.kmarket.navigator.backend.translation.domain;

import java.util.UUID;

import com.kmarket.navigator.backend.global.text.EnglishTextPolicy;

public record GeneratedTitle(
	UUID id,
	String sourceHash,
	String translatedText,
	String targetLocale,
	String translationVersion,
	String modelId,
	String promptVersion
) {
	public GeneratedTitle {
		EnglishTextPolicy.requireValid(translatedText);
	}
}
