package com.kmarket.navigator.backend.translation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.DisclosureQueryHandler;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDocument;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSection;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsContentAvailability;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.TranslationKind;
import com.kmarket.navigator.backend.translation.domain.TranslationStatus;
import com.kmarket.navigator.backend.translation.domain.TranslationView;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OnDemandTranslationService {

	public static final String NEWS_VERSION = "news-bilingual-v1";
	public static final String DISCLOSURE_SECTION_VERSION = "disclosure-section-v4";
	private final NewsRepository newsRepository;
	private final DisclosureQueryHandler disclosureQueryHandler;
	private final TranslationRepository translationRepository;
	private final TranslationCanonicalizer canonicalizer;
	private final TranslationRequestRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private static final Set<String> NEWS_LOCALES = Set.of("en", "ko");

	@Autowired
	public OnDemandTranslationService(
		NewsRepository newsRepository,
		DisclosureQueryHandler disclosureQueryHandler,
		TranslationRepository translationRepository,
		TranslationCanonicalizer canonicalizer,
		TranslationRequestRateLimiter rateLimiter,
		ObjectMapper objectMapper
	) {
		this(newsRepository, disclosureQueryHandler, translationRepository, canonicalizer,
			rateLimiter, objectMapper,
			Clock.systemUTC());
	}

	OnDemandTranslationService(
		NewsRepository newsRepository,
		DisclosureQueryHandler disclosureQueryHandler,
		TranslationRepository translationRepository,
		TranslationCanonicalizer canonicalizer,
		TranslationRequestRateLimiter rateLimiter,
		ObjectMapper objectMapper,
		Clock clock
	) {
		this.newsRepository = newsRepository;
		this.disclosureQueryHandler = disclosureQueryHandler;
		this.translationRepository = translationRepository;
		this.canonicalizer = canonicalizer;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
		this.clock = clock;
	}

	public TranslationView findNews(UUID articleId, String targetLocale) {
		String locale = newsLocale(targetLocale);
		NewsSource source = newsSource(articleId);
		return translationRepository.find(TranslationKind.NEWS_NARRATIVE,
			source.source().hash(), "en", NEWS_VERSION)
			.map(view -> newsView(view, locale))
			.or(() -> legacyNewsCache(source.source().hash(), locale))
			.orElseGet(() -> TranslationView.notRequested(source.source().hash(), locale, NEWS_VERSION));
	}

	public TranslationView requestNews(UUID articleId, String targetLocale, String clientHash) {
		var existing = findNews(articleId, targetLocale);
		rateLimiter.checkBatch(clientHash, needsRequest(existing) ? 1 : 0);
		if (!needsRequest(existing)) { prioritize(existing); return existing; }
		TranslationView view = ensureNewsRequested(articleId, targetLocale);
		prioritize(view);
		return view;
	}

	public TranslationView ensureNewsRequested(UUID articleId, String targetLocale) {
		String locale = newsLocale(targetLocale);
		NewsSource source = newsSource(articleId);
		var existing = translationRepository.find(TranslationKind.NEWS_NARRATIVE, source.source().hash(), "en", NEWS_VERSION);
		if (existing.isEmpty()) {
			var legacy = legacyNewsCache(source.source().hash(), locale);
			if (legacy.isPresent()) return legacy.get();
		}
		ObjectNode context = objectMapper.createObjectNode();
		context.put("article_id", articleId.toString());
		TranslationView shared = translationRepository.request(
			TranslationKind.NEWS_NARRATIVE,
			source.source().hash(),
			source.source().canonical(),
			context,
			"en",
			NEWS_VERSION,
			Instant.now(clock)
		);
		return newsView(shared, locale);
	}

	private java.util.Optional<TranslationView> legacyNewsCache(String hash, String locale) {
		var english = translationRepository.find(TranslationKind.NEWS_NARRATIVE, hash, "en", "news-narrative-v12");
		var korean = translationRepository.find(TranslationKind.NEWS_NARRATIVE, hash, "ko", "news-narrative-v12");
		// 같은 원문으로 양언어 생성이 이미 끝났다면 정책 전환만으로 재과금하지 않는다.
		if (english.isEmpty() || korean.isEmpty() || english.get().status() != TranslationStatus.READY
			|| korean.get().status() != TranslationStatus.READY) return java.util.Optional.empty();
		return "ko".equals(locale) ? korean : english;
	}

	private TranslationView newsView(TranslationView view, String locale) {
		var result = view.result();
		if (result != null && result.path("summaries").has(locale)) {
			ObjectNode localized = ((ObjectNode) result).deepCopy();
			var summary = result.path("summaries").path(locale);
			for (String key : List.of("what", "why", "impact")) localized.set(key, summary.path(key));
			// 다른 언어 필드를 영문 검증기나 본문 선택 영역으로 전달하지 않는다.
			localized.remove("summaries");
			if ("ko".equals(locale)) localized.remove("translatedParagraphs");
			result = localized;
		}
		return new TranslationView(view.jobId(), view.sourceHash(), locale, view.translationVersion(),
			view.status(), result, view.modelId(), view.promptVersion(), view.generatedAt(), view.errorCode());
	}

	public TranslationView findDisclosureSection(String receiptNumber, UUID sectionId) {
		DisclosureSource source = disclosureSource(receiptNumber, sectionId);
		return translationRepository.find(
			TranslationKind.DISCLOSURE_SECTION,
			source.source().hash(), "en",
			DISCLOSURE_SECTION_VERSION
		).orElseGet(() -> TranslationView.notRequested(
			source.source().hash(), "en", DISCLOSURE_SECTION_VERSION
		));
	}

	public List<DocumentTranslation> findDisclosureDocuments(String receiptNumber) {
		return disclosureQueryHandler.findOne(receiptNumber).documents().stream().map(document -> {
			var translations = new LinkedHashMap<UUID, TranslationView>();
			var sources = DisclosureHtmlRenderer.translationSections(document).stream()
				.map(section -> disclosureSource(document, section)).toList();
			var cached = translationRepository.findMany(TranslationKind.DISCLOSURE_SECTION,
				sources.stream().map(source -> source.source().hash()).toList(), "en", DISCLOSURE_SECTION_VERSION);
			for (var source : sources) {
				translations.put(source.sectionId(), cached.getOrDefault(source.source().hash(),
					TranslationView.notRequested(source.source().hash(), "en", DISCLOSURE_SECTION_VERSION)));
			}
			long ready = translations.values().stream().filter(view -> view.status() == TranslationStatus.READY).count();
			long failed = translations.values().stream().filter(view -> view.status() == TranslationStatus.FAILED).count();
			return new DocumentTranslation(document.id(), DisclosureHtmlRenderer.render(document, translations),
				translations.size(), ready, failed);
		}).toList();
	}

	public record DocumentTranslation(UUID documentId, String html, int total, long ready, long failed) { }

	public TranslationView requestDisclosureSection(
		String receiptNumber,
		UUID sectionId,
		String clientHash
	) {
		DisclosureSource source = disclosureSource(receiptNumber, sectionId);
		var existing = translationRepository.find(TranslationKind.DISCLOSURE_SECTION, source.source().hash(),
			"en", DISCLOSURE_SECTION_VERSION);
		rateLimiter.checkBatch(clientHash, existing.filter(view -> !needsRequest(view)).isPresent() ? 0 : 1);
		if (existing.filter(view -> !needsRequest(view)).isPresent()) { prioritize(existing.get()); return existing.get(); }
		return requestDisclosureSource(receiptNumber, source);
	}

	public List<TranslationView> requestDisclosure(String receiptNumber, String clientHash) {
		var detail = disclosureQueryHandler.findOne(receiptNumber);
		List<DisclosureSource> sources = detail.documents().stream()
			.flatMap(document -> DisclosureHtmlRenderer.translationSections(document).stream()
				.map(section -> disclosureSource(document, section)))
			.toList();
		var cached = translationRepository.findMany(TranslationKind.DISCLOSURE_SECTION,
			sources.stream().map(source -> source.source().hash()).toList(), "en", DISCLOSURE_SECTION_VERSION);
		long newWork = sources.stream().map(source -> source.source().hash()).distinct()
			.filter(hash -> needsRequest(cached.get(hash))).count();
		rateLimiter.checkBatch(clientHash, Math.toIntExact(newWork));
		return sources.stream().map(source -> {
			var existing = cached.get(source.source().hash());
			if (!needsRequest(existing)) { prioritize(existing); return existing; }
			return requestDisclosureSource(receiptNumber, source);
		}).toList();
	}

	private static boolean needsRequest(TranslationView view) {
		return view == null || view.status() == TranslationStatus.NOT_REQUESTED || view.status() == TranslationStatus.FAILED;
	}

	private TranslationView requestDisclosureSource(String receiptNumber, DisclosureSource source) {
		ObjectNode context = objectMapper.createObjectNode();
		context.put("receipt_number", receiptNumber);
		context.put("document_version", source.document().version());
		context.put("section_id", source.sectionId().toString());
		TranslationView view = translationRepository.request(
			TranslationKind.DISCLOSURE_SECTION,
			source.source().hash(),
			source.source().canonical(),
			context,
			"en",
			DISCLOSURE_SECTION_VERSION,
			Instant.now(clock)
		);
		prioritize(view);
		return view;
	}

	private void prioritize(TranslationView view) {
		if (view.jobId() != null && view.status() != TranslationStatus.READY) {
			translationRepository.prioritize(view.jobId(), Instant.now(clock));
		}
	}

	private String newsLocale(String targetLocale) {
		String locale = targetLocale == null ? "en" : targetLocale.strip().toLowerCase();
		if (!NEWS_LOCALES.contains(locale)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return locale;
	}

	private NewsSource newsSource(UUID articleId) {
		var article = newsRepository.findById(articleId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NEWS_NOT_FOUND));
		if (article.contentAvailability() == NewsContentAvailability.UNAVAILABLE
			|| article.sourceText() == null || article.sourceText().isBlank()) {
			throw new BusinessException(ErrorCode.SOURCE_CONTENT_UNAVAILABLE);
		}
		List<String> paragraphs = Arrays.stream(article.sourceText().split("\\R\\s*\\R"))
			.map(String::strip)
			.filter(value -> !value.isBlank())
			.toList();
		if (paragraphs.isEmpty()) {
			throw new BusinessException(ErrorCode.SOURCE_CONTENT_UNAVAILABLE);
		}
		return new NewsSource(
			canonicalizer.news(article.originalTitle(), paragraphs, article.contentAvailability().name())
		);
	}

	private DisclosureSource disclosureSource(String receiptNumber, UUID sectionId) {
		var detail = disclosureQueryHandler.findOne(receiptNumber);
		for (DisclosureDocument document : detail.documents()) {
			for (DisclosureSection section : DisclosureHtmlRenderer.translationSections(document)) {
				if (section.id().equals(sectionId)) {
					return disclosureSource(document, section);
				}
			}
		}
		throw new BusinessException(ErrorCode.DISCLOSURE_SECTION_NOT_FOUND);
	}

	private DisclosureSource disclosureSource(
		DisclosureDocument document,
		DisclosureSection section
	) {
		if (section.heading() == null && section.text() == null && section.tableData() == null) {
			throw new BusinessException(ErrorCode.SOURCE_CONTENT_UNAVAILABLE);
		}
		return new DisclosureSource(
			document,
			section.id(),
			canonicalizer.disclosureSection(
				section.heading(), section.text(), section.tableData()
			)
		);
	}

	private record NewsSource(TranslationCanonicalizer.Source source) {
	}

	private record DisclosureSource(
		DisclosureDocument document,
		UUID sectionId,
		TranslationCanonicalizer.Source source
	) {
	}
}
