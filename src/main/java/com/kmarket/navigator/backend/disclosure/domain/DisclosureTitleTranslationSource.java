package com.kmarket.navigator.backend.disclosure.domain;

public record DisclosureTitleTranslationSource(
	String sourceHash,
	String normalizedTitle,
	String status
) {
}
