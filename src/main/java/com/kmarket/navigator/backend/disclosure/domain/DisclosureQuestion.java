package com.kmarket.navigator.backend.disclosure.domain;

import java.util.UUID;

public record DisclosureQuestion(String question, SelectedContext selectedContext, String answerLocale) {
	public DisclosureQuestion(String question, SelectedContext selectedContext) {
		this(question, selectedContext, "auto");
	}
	public DisclosureQuestion {
		if (answerLocale == null) answerLocale = "auto";
		if (!answerLocale.equals("en") && !answerLocale.equals("ko") && !answerLocale.equals("auto")) throw new IllegalArgumentException("Unsupported answer locale");
	}

	public record SelectedContext(UUID sectionId, String text, String translationSourceHash) {
		public SelectedContext(UUID sectionId, String text) { this(sectionId, text, null); }
	}
}
