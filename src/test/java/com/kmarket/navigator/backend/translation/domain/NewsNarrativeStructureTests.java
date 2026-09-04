package com.kmarket.navigator.backend.translation.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class NewsNarrativeStructureTests {

	private ObjectNode result() {
		var result = JsonMapper.builder().build().createObjectNode();
		result.put("what", "A Korean label 高 and ₩700 appear.");
		result.put("why", "Natural punctuation—unchanged.");
		result.put("impact", "Not stated.");
		result.putArray("translatedParagraphs").add("₩700, 7,000 won; label 高.");
		return result;
	}

	@Test
	void acceptsExpressionWithoutLanguageOrCurrencyHeuristics() {
		assertThatCode(() -> NewsNarrativeStructure.requireValid(result(), 1, true)).doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingBlankAndNonTextParagraphs() {
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(result(), 2, true))
			.isInstanceOf(IllegalArgumentException.class);
		var blank = result();
		blank.putArray("translatedParagraphs").add("  ");
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(blank, 1, true))
			.isInstanceOf(IllegalArgumentException.class);
		blank.putArray("translatedParagraphs").add(42);
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(blank, 1, true))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void progressDoesNotRequireBodyButCompletionDoes() {
		var progress = result();
		progress.remove("translatedParagraphs");
		progress.put("bodyReady", false);
		assertThatCode(() -> NewsNarrativeStructure.requireValid(progress, 1, false)).doesNotThrowAnyException();
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(progress, 1, true))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingSummaryFields() {
		var incomplete = result();
		incomplete.remove("why");
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(incomplete, 1, false))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
