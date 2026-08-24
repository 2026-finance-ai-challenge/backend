package com.kmarket.navigator.backend.disclosure.domain;

import java.util.UUID;

public record DisclosureTitleTranslationJob(
	UUID translationId,
	String sourceHash,
	String normalizedTitle,
	int attempts
) {
}
