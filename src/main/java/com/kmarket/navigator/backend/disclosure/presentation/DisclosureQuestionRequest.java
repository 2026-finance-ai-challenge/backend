package com.kmarket.navigator.backend.disclosure.presentation;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;

record DisclosureQuestionRequest(
	@NotBlank @Size(max = 2_000) String question,
	@Valid SelectedContext selectedContext
) {
	DisclosureQuestion toDomain() {
		return new DisclosureQuestion(
			question,
			selectedContext == null
				? null
				: new DisclosureQuestion.SelectedContext(selectedContext.sectionId(), selectedContext.text())
		);
	}

	record SelectedContext(
		@NotNull UUID sectionId,
		@NotBlank @Size(max = 2_000) String text
	) {
	}
}
