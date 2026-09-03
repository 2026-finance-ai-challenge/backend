package com.kmarket.navigator.backend.translation.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;

@Service
public class NewsSelectionValidator {
	private final TranslationRepository repository;
	private final TranslationCanonicalizer canonicalizer;

	public NewsSelectionValidator(TranslationRepository repository, TranslationCanonicalizer canonicalizer) {
		this.repository = repository;
		this.canonicalizer = canonicalizer;
	}

	public Selection validate(NewsArticle article, String requestedText) {
		if (requestedText == null || requestedText.isBlank()) return null;
		String selected = normalize(requestedText);
		if (selected.isEmpty() || selected.length() > 2_000 || article.originalBody() == null || article.originalBody().isBlank()) throw invalid();
		String hash = canonicalizer.news(article).hash();
		String source = normalize(article.originalBody());
		if (source.contains(selected)) return new Selection(selected, "ko", hash, window(source, selected));
		// 현재 기사 해시의 완료 본문만 조회한다. 다른 기사·변경 전 원문·요약만 완료된 캐시는 제외한다.
		for (String version : List.of(OnDemandTranslationService.NEWS_VERSION, "news-narrative-v12")) {
			var cached = repository.find(TranslationKind.NEWS_NARRATIVE, hash, "en", version);
			if (cached.isEmpty() || cached.get().status() != TranslationStatus.READY || cached.get().result() == null) continue;
			var paragraphs = cached.get().result().path("translatedParagraphs");
			if (!paragraphs.isArray() || paragraphs.isEmpty()) continue;
			var parts = new ArrayList<String>();
			for (var paragraph : paragraphs) {
				if (!paragraph.isString()) throw invalid();
				parts.add(paragraph.asString());
			}
			String translated = normalize(String.join(" ", parts));
			if (translated.contains(selected)) return new Selection(selected, "en", hash, window(translated, selected));
		}
		throw invalid();
	}

	private static String window(String source, String selected) {
		int position = source.indexOf(selected);
		int start = Math.max(0, position - 1_000);
		return source.substring(start, Math.min(source.length(), position + selected.length() + 1_000));
	}

	private static String normalize(String value) { return value.replaceAll("[\\s\\p{Z}]+", " ").strip(); }
	private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_CHAT_SELECTION); }
	public record Selection(String text, String language, String sourceHash, String context) { }
}
