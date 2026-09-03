package com.kmarket.navigator.backend.translation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsContentAvailability;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.*;
import com.kmarket.navigator.backend.global.error.BusinessException;
import tools.jackson.databind.json.JsonMapper;

class NewsSelectionValidatorTests {
	private final TranslationRepository repository = mock(TranslationRepository.class);
	private final JsonMapper mapper = new JsonMapper();
	private final TranslationCanonicalizer canonicalizer = new TranslationCanonicalizer(mapper);
	private final NewsSelectionValidator validator = new NewsSelectionValidator(repository, canonicalizer);
	private final NewsArticle article = mock(NewsArticle.class);
	private final String body = "배당 계획은 확정되지 않았다.\n\n해외 인수는 아직 전망이다.";

	NewsSelectionValidatorTests() {
		when(article.originalTitle()).thenReturn("배당과 인수 전망");
		when(article.originalBody()).thenReturn(body);
		when(article.sourceText()).thenReturn(body);
		when(article.contentAvailability()).thenReturn(NewsContentAvailability.FULL_ARTICLE);
	}
	private void cached(String version, TranslationStatus status, String result) {
		String hash = canonicalizer.news(article).hash();
		when(repository.find(TranslationKind.NEWS_NARRATIVE, hash, "en", version)).thenReturn(Optional.of(
			new TranslationView(UUID.randomUUID(), hash, "en", version, status, mapper.readTree(result), null, null, null, null)));
	}
	@Test
	void validatesKoreanSelectionAcrossWhitespaceWithoutTranslationLookup() {
		var selected = validator.validate(article, "확정되지 않았다.\t해외 인수");
		assertThat(selected.language()).isEqualTo("ko");
		assertThat(selected.text()).isEqualTo("확정되지 않았다. 해외 인수");
		assertThat(selected.context()).contains(selected.text());
		verifyNoInteractions(repository);
	}
	@Test
	void readsCurrentReadyEnglishParagraphsWithoutRequestingGeneration() {
		cached(OnDemandTranslationService.NEWS_VERSION, TranslationStatus.READY,
			"{\"translatedParagraphs\":[\"Dividends are not confirmed.\",\"An acquisition remains an outlook.\"]}");
		var selected = validator.validate(article, "not confirmed.\nAn acquisition");
		assertThat(selected.language()).isEqualTo("en");
		assertThat(selected.sourceHash()).isEqualTo(canonicalizer.news(article).hash());
		verify(repository).find(TranslationKind.NEWS_NARRATIVE, selected.sourceHash(), "en", OnDemandTranslationService.NEWS_VERSION);
		verifyNoMoreInteractions(repository);
	}
	@Test
	void supportsExistingReadyEnglishCacheWithoutRegeneration() {
		cached("news-narrative-v12", TranslationStatus.READY, "{\"translatedParagraphs\":[\"Dividends are not confirmed.\"]}");
		assertThat(validator.validate(article, "Dividends").language()).isEqualTo("en");
	}
	@Test
	void rejectsFailedOrSummaryOnlyBodyCache() {
		for (var status : new TranslationStatus[]{TranslationStatus.FAILED, TranslationStatus.PROCESSING}) {
			cached(OnDemandTranslationService.NEWS_VERSION, status, "{\"summaryReady\":true,\"translatedParagraphs\":[\"Dividends\"]}");
			assertThatThrownBy(() -> validator.validate(article, "Dividends")).isInstanceOf(BusinessException.class);
		}
	}
	@Test
	void rejectsAnotherArticlesTextOrStaleHash() {
		cached(OnDemandTranslationService.NEWS_VERSION, TranslationStatus.READY, "{\"translatedParagraphs\":[\"Dividends are not confirmed.\"]}");
		assertThatThrownBy(() -> validator.validate(article, "Buy now")).isInstanceOf(BusinessException.class);
		when(article.originalTitle()).thenReturn("변경된 기사");
		assertThatThrownBy(() -> validator.validate(article, "Dividends")).isInstanceOf(BusinessException.class);
	}
	@Test
	void keepsSelectedContextFromEndOfALongArticle() {
		String longBody = "앞부분 ".repeat(5_000) + "아직 확정되지 않은 인수 전망";
		when(article.originalBody()).thenReturn(longBody);
		when(article.sourceText()).thenReturn(longBody);
		var selected = validator.validate(article, "확정되지 않은 인수 전망");
		assertThat(selected.context()).contains(selected.text()).hasSizeLessThan(4_001);
		verifyNoInteractions(repository);
	}
}
