package com.kmarket.navigator.backend.disclosure.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisclosureTitlePolicyTests {

	@Test
	void normalizesWhitespaceBeforeHashing() {
		String normalized = DisclosureTitlePolicy.normalize("  사업보고서   (2025.12)  ");

		assertThat(normalized).isEqualTo("사업보고서 (2025.12)");
		assertThat(DisclosureTitlePolicy.sourceHash(normalized))
			.isEqualTo(DisclosureTitlePolicy.sourceHash("사업보고서 (2025.12)"))
			.matches("[0-9a-f]{64}");
	}
}
