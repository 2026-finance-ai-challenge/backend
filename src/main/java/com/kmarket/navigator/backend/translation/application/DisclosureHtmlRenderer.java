package com.kmarket.navigator.backend.translation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.disclosure.domain.SectionKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;
import tools.jackson.databind.JsonNode;

public final class DisclosureHtmlRenderer {

	private DisclosureHtmlRenderer() { }

	public static List<DisclosureSection> translationSections(DisclosureDocument source) {
		if (source.originalHtml() == null || source.originalHtml().isBlank()) return source.sections();
		var tables = Jsoup.parseBodyFragment(source.originalHtml()).select("table").stream()
			.filter(table -> table.parents().stream().noneMatch(parent -> parent.tagName().equals("table")))
			.filter(table -> table.select("table").size() > 1).toList();
		if (tables.isEmpty()) return source.sections();
		var matrices = new java.util.HashMap<String, String>();
		var mapper = new tools.jackson.databind.ObjectMapper();
		// 과거 표 행렬은 중첩 표의 내용을 부모 셀에도 중복 저장했다. 번역 입력만 원본 HTML에서 복원한다.
		for (var table : tables) {
			var rows = table.select("tr").stream().map(row -> row.children().stream()
				.filter(cell -> cell.tagName().equals("td") || cell.tagName().equals("th"))
				.map(cell -> { var own = cell.clone(); own.select("table").remove(); return normalize(own.text()); }).toList())
				.filter(row -> !row.isEmpty()).toList();
			matrices.put(compact(visibleText(table)), mapper.writeValueAsString(rows));
		}
		return source.sections().stream().map(section -> section.kind() == SectionKind.TABLE
			&& matrices.containsKey(compact(section.text())) ? new DisclosureSection(section.id(), section.ordinal(),
			section.kind(), section.heading(), section.text(), matrices.get(compact(section.text()))) : section).toList();
	}

	public static String render(DisclosureDocument source, Map<UUID, TranslationView> translations) {
		if (source.originalHtml() == null || source.originalHtml().isBlank()) return null;
		var document = Jsoup.parseBodyFragment(source.originalHtml());
		document.outputSettings().prettyPrint(false);
		List<Node> blocks = new ArrayList<>();
		collect(document.body(), blocks);
		List<DisclosureSection> remaining = new ArrayList<>(source.sections());
		for (Node node : blocks) {
			boolean table = node instanceof Element element && element.tagName().equals("table");
			String original = visibleText(node);
			var match = remaining.stream().filter(section ->
				(table == (section.kind() == SectionKind.TABLE)) && compact(section.text()).equals(compact(original))
			).findFirst();
			if (match.isEmpty() && node instanceof TextNode) {
				// XML 전용 태그 제거로 합쳐진 텍스트는 기존 섹션 순서와 전체 문자열이 일치할 때만 분리한다.
				String unmatched = compact(original);
				var sections = new ArrayList<DisclosureSection>();
				var visibleRemaining = remaining.stream().filter(candidate -> candidate.ordinal() != 0
					|| candidate.kind() != SectionKind.TITLE || compact(original).startsWith(compact(candidate.text()))).toList();
				for (var candidate : visibleRemaining) {
					if (candidate.kind() == SectionKind.TABLE) break;
					String key = compact(candidate.text());
					if (key.isEmpty() || !unmatched.startsWith(key)) break;
					sections.add(candidate);
					unmatched = unmatched.substring(key.length());
					if (unmatched.isEmpty()) break;
				}
				if (unmatched.isEmpty() && !sections.isEmpty()) {
					for (var section : sections) {
						var span = new Element("span");
						node.before(span);
						applySection(span, section, translations.get(section.id()), false);
						remaining.remove(section);
					}
					node.remove();
					continue;
				}
			}
			if (match.isEmpty()) throw new IllegalStateException("Disclosure HTML section mapping failed: block="
				+ blocks.indexOf(node) + ", pending=" + remaining.stream().limit(5).map(DisclosureSection::ordinal).toList());
			var section = match.get();
			remaining.remove(section);
			if (node instanceof TextNode) {
				var span = new Element("span");
				node.replaceWith(span);
				applySection(span, section, translations.get(section.id()), false);
			} else applySection(node, section, translations.get(section.id()), table);
		}
		if (remaining.stream().anyMatch(section -> section.ordinal() != 0 || section.kind() != SectionKind.TITLE)) {
			throw new IllegalStateException("Stored sections are missing from source HTML");
		}
		return document.body().html();
	}

	private static void applySection(Node node, DisclosureSection section, TranslationView translated, boolean table) {
			boolean ready = translated != null && translated.status() == TranslationStatus.READY;
			if (ready && table) applyTable((Element) node, translated.result().path("translatedTableData"));
			else if (ready) replaceText(node, translated.result().path("translatedText").asString());
			else {
				replaceText(node, "…");
				if (node instanceof Element element) element.addClass("translation-placeholder");
			}
			if (node instanceof Element element) {
				element.attr("data-section-id", section.id().toString());
				if (ready) element.addClass("selection-content");
			}
	}

	private static void applyTable(Element table, JsonNode translated) {
		var rows = table.select("tr").stream().filter(row -> row.children().stream()
			.anyMatch(child -> child.tagName().equals("td") || child.tagName().equals("th"))).toList();
		if (!translated.isArray() || translated.size() != rows.size()) throw new IllegalStateException("Table row mismatch");
		for (int row = 0; row < rows.size(); row++) {
			var cells = rows.get(row).children().stream()
				.filter(cell -> cell.tagName().equals("td") || cell.tagName().equals("th")).toList();
			if (cells.size() != translated.path(row).size()) throw new IllegalStateException("Table cell mismatch");
			for (int cell = 0; cell < cells.size(); cell++) replaceCellText(cells.get(cell), translated.path(row).path(cell).asString());
		}
	}

	private static void replaceCellText(Element cell, String translated) {
		List<TextNode> texts = new ArrayList<>();
		collectCellText(cell, texts);
		if (texts.isEmpty()) cell.prependText(translated);
		else {
			texts.getFirst().text(translated);
			texts.stream().skip(1).forEach(text -> text.text(""));
		}
	}

	private static void collectCellText(Node node, List<TextNode> result) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode text) result.add(text);
			else if (!(child instanceof Element element && element.tagName().equals("table"))) collectCellText(child, result);
		}
	}

	private static void replaceText(Node node, String translated) {
		if (node instanceof TextNode text) { text.text(translated); return; }
		List<TextNode> texts = new ArrayList<>();
		collectText(node, texts);
		if (texts.isEmpty()) ((Element) node).appendText(translated);
		else {
			texts.getFirst().text(translated);
			texts.stream().skip(1).forEach(text -> text.text(""));
		}
	}

	private static void collectText(Node node, List<TextNode> result) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode text) result.add(text);
			else collectText(child, result);
		}
	}

	private static void collect(Node node, List<Node> result) {
		if (node instanceof TextNode text) { if (!normalize(text.text()).isBlank()) result.add(node); return; }
		if (!(node instanceof Element element)) return;
		if (List.of("script", "style", "noscript").contains(element.tagName())) return;
		if (List.of("table", "p", "pre", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6").contains(element.tagName())) {
			if (!normalize(element.text()).isBlank()) result.add(node);
			return;
		}
		for (Node child : node.childNodes()) collect(child, result);
	}

	private static String normalize(String value) { return value == null ? "" : value.replaceAll("[\\s\\p{Z}]+", " ").strip(); }
	private static String compact(String value) { return normalize(value).replace(" ", ""); }

	private static String visibleText(Node node) {
		List<TextNode> texts = new ArrayList<>();
		if (node instanceof TextNode text) return normalize(text.text());
		collectText(node, texts);
		return texts.stream().map(text -> normalize(text.text())).filter(text -> !text.isBlank())
			.collect(java.util.stream.Collectors.joining(" "));
	}
}
