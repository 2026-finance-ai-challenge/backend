package com.kmarket.navigator.backend.disclosure.infrastructure.translation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResourceDisclosureTitleTranslationCatalogTests {

	private final ResourceDisclosureTitleTranslationCatalog catalog =
		new ResourceDisclosureTitleTranslationCatalog();

	@Test
	void translatesExactModifierPeriodAndContextTitles() {
		assertThat(catalog.translate("임원ㆍ주요주주특정증권등소유상황보고서"))
			.contains("Report on Ownership of Specified Securities by Officers and Major Shareholders");
		assertThat(catalog.translate("[기재정정]사업보고서 (2025.12)"))
			.contains("[Amended] Annual Report (2025.12)");
		assertThat(catalog.translate("현금ㆍ현물배당결정(자회사의 주요경영사항)"))
			.contains("Cash or In-Kind Dividend Decision (Material Management Matter of a Subsidiary)");
		assertThat(catalog.translate(
			"투자판단관련주요경영사항(자회사의 주요경영사항)(제미다파엠서방정 국내 품목허가 신청)"
		))
			.contains(
				"Material Management Matter Related to Investment Decisions "
					+ "(Korean Marketing Authorization Application for Zemidapa-M Extended-Release Tablets) "
					+ "(Material Management Matter of a Subsidiary)"
			);
	}

	@Test
	void refusesUnknownTitleInsteadOfGuessing() {
		assertThat(catalog.translate("검수되지않은새공시")).isEmpty();
		assertThat(catalog.reviewKey("[기재정정]검수되지않은새공시(자율공시) (2025.12)"))
			.isEqualTo("검수되지않은새공시");
	}
}
