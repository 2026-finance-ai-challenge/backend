package com.kmarket.navigator.backend.translation.domain;

import java.util.UUID;

public record TitleTranslationJob(
	UUID id,
	String sourceHash,
	String sourceText,
	String translationVersion,
	int attempts
) {
}
