package com.kmarket.navigator.backend.translation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;

import tools.jackson.databind.JsonNode;

@Service
public class DisclosureSelectionValidator {
	private final TranslationRepository translations;
	private final TranslationCanonicalizer canonicalizer;

	public DisclosureSelectionValidator(TranslationRepository translations, TranslationCanonicalizer canonicalizer) {
		this.translations = translations;
		this.canonicalizer = canonicalizer;
	}

	public DisclosureQuestion.SelectedContext validate(DisclosureDetail detail, UUID sectionId, String text) {
		if (sectionId == null && (text == null || text.isBlank())) return null;
		String selected = normalize(text);
		if (sectionId == null || selected.isEmpty() || selected.length() > 2_000) throw invalid();
		var section = detail.documents().stream()
			.flatMap(document -> DisclosureHtmlRenderer.translationSections(document).stream())
			.filter(candidate -> candidate.id().equals(sectionId)).findFirst().orElseThrow(this::invalid);
		if (normalize(section.text()).contains(selected)) return new DisclosureQuestion.SelectedContext(sectionId, selected);
		String hash = canonicalizer.disclosureSection(section.heading(), section.text(), section.tableData()).hash();
		var cached = translations.find(TranslationKind.DISCLOSURE_SECTION, hash, "en",
			OnDemandTranslationService.DISCLOSURE_SECTION_VERSION).orElseThrow(this::invalid);
		if (cached.status() != TranslationStatus.READY || cached.result() == null) throw invalid();
		var parts = new ArrayList<String>();
		collectText(cached.result().path("translatedTableData"), parts);
		String translatedText = normalize(cached.result().path("translatedText").asString(""));
		if (!translatedText.contains(selected) && !normalize(String.join(" ", parts)).contains(selected)) throw invalid();
		// 현재 원문 해시의 완료 캐시로 검증한 영문 선택만 RAG에 전달한다. 번역은 새로 생성하지 않는다.
		return new DisclosureQuestion.SelectedContext(sectionId, selected, hash);
	}

	private void collectText(JsonNode node, List<String> parts) {
		if (node.isArray()) node.forEach(child -> collectText(child, parts));
		else if (node.isString()) parts.add(node.asString());
	}

	private String normalize(String text) { return text == null ? "" : text.replaceAll("[\\s\\p{Z}]+", " ").strip(); }
	private BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_CHAT_SELECTION); }
}
