package com.kmarket.navigator.backend.translation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

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

	public static final String NEWS_VERSION = "news-narrative-v9";
	public static final String DISCLOSURE_SECTION_VERSION = "disclosure-section-v4";
	private final NewsRepository newsRepository;
	private final DisclosureQueryHandler disclosureQueryHandler;
	private final TranslationRepository translationRepository;
	private final TranslationCanonicalizer canonicalizer;
	private final TranslationRequestRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;
	private final Clock clock;

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

	public TranslationView findNews(UUID articleId) {
		NewsSource source = newsSource(articleId);
		return translationRepository.find(TranslationKind.NEWS_NARRATIVE,
			source.source().hash(), NEWS_VERSION)
			.orElseGet(() -> TranslationView.notRequested(source.source().hash(), NEWS_VERSION));
	}

	public TranslationView requestNews(UUID articleId, String clientHash) {
		rateLimiter.check(clientHash);
		TranslationView view = ensureNewsRequested(articleId);
		prioritize(view);
		return view;
	}

	public TranslationView ensureNewsRequested(UUID articleId) {
		NewsSource source = newsSource(articleId);
		ObjectNode context = objectMapper.createObjectNode();
		context.put("article_id", articleId.toString());
		return translationRepository.request(
			TranslationKind.NEWS_NARRATIVE,
			source.source().hash(),
			source.source().canonical(),
			context,
			NEWS_VERSION,
			Instant.now(clock)
		);
	}

	public TranslationView findDisclosureSection(String receiptNumber, UUID sectionId) {
		DisclosureSource source = disclosureSource(receiptNumber, sectionId);
		return translationRepository.find(
			TranslationKind.DISCLOSURE_SECTION,
			source.source().hash(),
			DISCLOSURE_SECTION_VERSION
		).orElseGet(() -> TranslationView.notRequested(
			source.source().hash(), DISCLOSURE_SECTION_VERSION
		));
	}

	public TranslationView requestDisclosureSection(
		String receiptNumber,
		UUID sectionId,
		String clientHash
	) {
		rateLimiter.check(clientHash);
		DisclosureSource source = disclosureSource(receiptNumber, sectionId);
		ObjectNode context = objectMapper.createObjectNode();
		context.put("receipt_number", receiptNumber);
		context.put("document_version", source.document().version());
		context.put("section_id", sectionId.toString());
		TranslationView view = translationRepository.request(
			TranslationKind.DISCLOSURE_SECTION,
			source.source().hash(),
			source.source().canonical(),
			context,
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
			for (DisclosureSection section : document.sections()) {
				if (section.id().equals(sectionId)) {
					if (section.heading() == null && section.text() == null
						&& section.tableData() == null) {
						throw new BusinessException(ErrorCode.SOURCE_CONTENT_UNAVAILABLE);
					}
					return new DisclosureSource(
						document,
						canonicalizer.disclosureSection(
							section.heading(), section.text(), section.tableData()
						)
					);
				}
			}
		}
		throw new BusinessException(ErrorCode.DISCLOSURE_SECTION_NOT_FOUND);
	}

	private record NewsSource(TranslationCanonicalizer.Source source) {
	}

	private record DisclosureSource(
		DisclosureDocument document,
		TranslationCanonicalizer.Source source
	) {
	}
}
