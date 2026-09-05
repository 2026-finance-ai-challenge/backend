package com.kmarket.navigator.backend.translation.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class NewsNarrativeStructureTests {

	private ObjectNode result() {
		var result = JsonMapper.builder().build().createObjectNode();
		result.put("what", "A KRW 700 label appears.");
		result.put("why", "Natural punctuation—unchanged.");
		result.put("impact", "Not stated.");
		result.putArray("translatedParagraphs").add("KRW 700 and KRW 7,000 appear.");
		return result;
	}

	@Test
	void acceptsCompleteEnglishNarrative() {
		assertThatCode(() -> NewsNarrativeStructure.requireValid(result(), 1, true)).doesNotThrowAnyException();
	}

	@Test
	void rejectsUntranslatedEnglishBodyAndSummary() {
		var untranslatedBody = result();
		untranslatedBody.putArray("translatedParagraphs").add("매출이 증가했다.");
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(untranslatedBody, 1, true))
			.isInstanceOf(IllegalArgumentException.class);
		var untranslatedSummary = result();
		untranslatedSummary.put("what", "매출이 증가했다.");
		assertThatThrownBy(() -> NewsNarrativeStructure.requireValid(untranslatedSummary, 1, false))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void allowsKoreanSummaryOnlyInsideKoreanLocale() {
		var bilingual = result();
		var summaries = bilingual.putObject("summaries");
		summaries.set("en", result().without("translatedParagraphs"));
		var korean = summaries.putObject("ko");
		korean.put("what", "매출이 증가했다.");
		korean.put("why", "해외 수요가 늘었다.");
		korean.put("impact", "실적에 긍정적이다.");
		assertThatCode(() -> NewsNarrativeStructure.requireValid(bilingual, 1, true)).doesNotThrowAnyException();
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
