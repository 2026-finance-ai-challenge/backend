package com.kmarket.navigator.backend.translation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class TranslationCanonicalizerTests {

	private final TranslationCanonicalizer canonicalizer = new TranslationCanonicalizer(
		JsonMapper.builder().build()
	);

	@Test
	void matchesAiNewsCanonicalHashContract() {
		var source = canonicalizer.news(
			"제목", List.of("문단 1", "문단 2"), "SOURCE_EXCERPT"
		);

		assertThat(source.canonical()).isEqualTo(
			"{\"content_availability\":\"SOURCE_EXCERPT\",\"paragraphs\":[\"문단 1\",\"문단 2\"],\"title\":\"제목\"}"
		);
		assertThat(source.hash()).isEqualTo(
			"86aa9b91e7a1a11546e907c4c11336121a8e576ae689550831e53f7f6df68432"
		);
	}

	@Test
	void matchesAiDisclosureSectionCanonicalHashContract() {
		var source = canonicalizer.disclosureSection("제목", "본문", "{\"항목\":\"값\"}");

		assertThat(source.canonical()).isEqualTo(
			"{\"heading\":\"제목\",\"table_data_json\":\"{\\\"항목\\\":\\\"값\\\"}\",\"text\":\"본문\"}"
		);
		assertThat(source.hash()).isEqualTo(
			"7eeec9dfa5bbc38484ca8f4512729da678f7ff96b8f5d7859de0b537bf33974e"
		);
	}
}
