package com.kmarket.navigator.backend.disclosure.domain;

import java.util.UUID;

public record DisclosureQuestion(String question, SelectedContext selectedContext) {

	public record SelectedContext(UUID sectionId, String text, String translationSourceHash) {
		public SelectedContext(UUID sectionId, String text) { this(sectionId, text, null); }
	}
}
