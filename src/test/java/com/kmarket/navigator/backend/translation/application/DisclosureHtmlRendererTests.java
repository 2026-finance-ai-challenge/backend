package com.kmarket.navigator.backend.translation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;
import tools.jackson.databind.json.JsonMapper;

class DisclosureHtmlRendererTests {
	@Test
	void annotatesOriginalWithoutChangingTextFormattingOrNestedTables() {
		UUID id = UUID.randomUUID();
		String html = "<table width='600'><tr><td rowspan='2'><b>매출</b><table><tr><td>100</td></tr></table></td></tr></table>";
		var source = new DisclosureDocument(UUID.randomUUID(), "original", 1, "hash", html,
			List.of(new DisclosureSection(id, 0, SectionKind.TABLE, null, "매출 100", "[[\"매출\",\"100\"]]")));
		var doc = Jsoup.parseBodyFragment(DisclosureHtmlRenderer.annotateOriginal(source));
		assertThat(doc.text()).isEqualTo(Jsoup.parseBodyFragment(html).text());
		assertThat(doc.select("table")).hasSize(2);
		assertThat(doc.select("table[data-section-id]").attr("data-section-id")).isEqualTo(id.toString());
		assertThat(doc.select("td").first().attr("rowspan")).isEqualTo("2");
		assertThat(doc.select("b").text()).isEqualTo("매출");
	}

	@Test
	void preservesExactWhitespaceWhenAnnotatingMergedLegacyText() {
		String html = " 주식회사\n공시　제출 ";
		var source = new DisclosureDocument(UUID.randomUUID(), "legacy", 1, "hash", html,
			List.of(new DisclosureSection(UUID.randomUUID(), 0, SectionKind.TEXT, null, "주식회사", null),
				new DisclosureSection(UUID.randomUUID(), 1, SectionKind.TEXT, null, "공시 제출", null)));
		var doc = Jsoup.parseBodyFragment(DisclosureHtmlRenderer.annotateOriginal(source));
		assertThat(doc.select("span")).hasSize(2);
		assertThat(doc.body().wholeText()).isEqualTo(html);
	}

	@Test
	void keepsUnmappedOriginalReadableWithoutInventingSelectionIds() {
		var source = new DisclosureDocument(UUID.randomUUID(), "a", 1, "hash", "<p>미대응 원문</p>", List.of());
		assertThat(DisclosureHtmlRenderer.annotateOriginal(source)).isEqualTo(source.originalHtml());
	}

	@Test
	void translatesNestedTablesWithoutDuplicatingOrRemovingInnerCells() {
		UUID id = UUID.randomUUID();
		var section = new DisclosureSection(id, 0, SectionKind.TABLE, null, "매출 이익 100", "[[\"매출 이익 100\"],[\"이익\",\"100\"]]");
		var source = new DisclosureDocument(UUID.randomUUID(), "nested", 1, "hash",
			"<table><tr><td>매출<table><tr><th>이익</th><td>100</td></tr></table></td></tr></table>", List.of(section));
		var inputs = DisclosureHtmlRenderer.translationSections(source);
		assertThat(inputs.getFirst().tableData()).isEqualTo("[[\"매출\"],[\"이익\",\"100\"]]");
		assertThat(inputs.getFirst().id()).isEqualTo(id);
		assertThat(source.sections().getFirst().tableData()).isEqualTo(section.tableData());
		var result = JsonMapper.builder().build().readTree("{\"translatedTableData\":[[\"Revenue\"],[\"Profit\",\"100\"]]}");
		var translation = new TranslationView(UUID.randomUUID(), "hash", "en", "v1", TranslationStatus.READY, result, "test", "test", null, null);
		var doc = Jsoup.parseBodyFragment(DisclosureHtmlRenderer.render(source, Map.of(id, translation)));
		assertThat(doc.select("table")).hasSize(2);
		assertThat(doc.select("th").text()).isEqualTo("Profit");
		assertThat(doc.text()).isEqualTo("Revenue Profit 100");
	}

	@Test
	void mapsMergedLegacyXmlTextWithUnicodeSpacesInSectionOrder() {
		var source = new DisclosureDocument(UUID.randomUUID(), "legacy", 1, "hash", "주식회사 공시　제출",
			List.of(new DisclosureSection(UUID.randomUUID(), 0, SectionKind.TEXT, null, "주식회사", null),
				new DisclosureSection(UUID.randomUUID(), 1, SectionKind.TEXT, null, "공시 제출", null)));
		assertThat(Jsoup.parseBodyFragment(DisclosureHtmlRenderer.render(source, Map.of())).select("span")).hasSize(2);
	}

	@Test
	void preservesTableStructureAndEscapesTranslatedMarkup() {
		UUID id = UUID.randomUUID();
		var source = new DisclosureDocument(UUID.randomUUID(), "a", 1, "hash",
			"<table width='600'><tbody><tr><th rowspan='2'>매출</th><td>100</td></tr><tr><td>200</td></tr></tbody></table>",
			List.of(new DisclosureSection(id, 0, SectionKind.TABLE, null, "매출 100 200", "[[\"매출\",\"100\"],[\"200\"]]")));
		var result = JsonMapper.builder().build().readTree("{\"translatedTableData\":[[\"Revenue <script>\",\"100\"],[\"200\"]]}");
		var translation = new TranslationView(UUID.randomUUID(), "hash", "en", "v1", TranslationStatus.READY, result, "test", "test", null, null);
		var html = DisclosureHtmlRenderer.render(source, Map.of(id, translation));
		var doc = Jsoup.parseBodyFragment(html);
		assertThat(doc.select("table").attr("width")).isEqualTo("600");
		assertThat(doc.select("th").attr("rowspan")).isEqualTo("2");
		assertThat(doc.select("tr")).hasSize(2);
		assertThat(doc.select("script")).isEmpty();
		assertThat(doc.text()).isEqualTo("Revenue <script> 100 200");
	}

	@Test
	void marksUntranslatedContentAsNonSelectableSkeleton() {
		UUID id = UUID.randomUUID();
		var source = new DisclosureDocument(UUID.randomUUID(), "a", 1, "hash", "<p>미완료</p>",
			List.of(new DisclosureSection(id, 0, SectionKind.TEXT, null, "미완료", null)));
		var html = DisclosureHtmlRenderer.render(source, Map.of());
		assertThat(html).contains("translation-placeholder").doesNotContain("미완료", "selection-content");
	}

	@Test
	void doesNotGuessWhenSourceStructureAndSectionsDisagree() {
		var source = new DisclosureDocument(UUID.randomUUID(), "a", 1, "hash", "<p>다른 원문</p>", List.of());
		assertThatThrownBy(() -> DisclosureHtmlRenderer.render(source, Map.of())).isInstanceOf(IllegalStateException.class);
	}
}
