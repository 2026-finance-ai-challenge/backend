package com.kmarket.navigator.backend.disclosure.domain;

import java.util.UUID;

public record DisclosureQuestion(String question, SelectedContext selectedContext) {

	public record SelectedContext(UUID sectionId, String text) {
	}
}
