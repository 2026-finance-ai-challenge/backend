package com.kmarket.navigator.backend.disclosure.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationCatalog;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureTitleTranslationRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureTitleTranslationJob;
import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

class DisclosureTitleTranslationWorkerTests {

	private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

	@Test
	void persistsOtherFilingTitlesAfterOneStorageFailure() {
		var repository = Mockito.mock(DisclosureTitleTranslationRepository.class);
		var catalog = Mockito.mock(DisclosureTitleTranslationCatalog.class);
		var gateway = Mockito.mock(TranslationAiGateway.class);
		var translations = Mockito.mock(TranslationRepository.class);
		var first = new DisclosureTitleTranslationJob(UUID.randomUUID(), "a".repeat(64), "공시 하나", 1);
		var second = new DisclosureTitleTranslationJob(UUID.randomUUID(), "b".repeat(64), "공시 둘", 1);
		var generated = List.of(first, second).stream().map(job -> new GeneratedTitle(job.translationId(),
			job.sourceHash(), "Filing by Jo", "en", DisclosureTitlePolicy.TRANSLATION_VERSION,
			"gpt-5-nano", "financial-title-translation-v8")).toList();
		when(repository.claimJobs(eq(25), any(), eq(NOW), eq(NOW.minus(Duration.ofMinutes(5)))))
			.thenReturn(List.of(first, second));
		when(catalog.translate(any())).thenReturn(Optional.empty());
		when(gateway.translateTitles(any())).thenReturn(generated);
		Mockito.doThrow(new IllegalStateException("storage failure")).when(repository)
			.complete(eq(first.translationId()), any(), any(), any(), eq(NOW));

		new DisclosureTitleTranslationWorker(repository, catalog, gateway, translations,
			Clock.fixed(NOW, ZoneOffset.UTC), "worker").processBatch(25);

		verify(repository).complete(second.translationId(), "Filing by Jo", "gpt-5-nano", "financial-title-translation-v8", NOW);
		verify(translations).fail(first.translationId(), 1, "IllegalStateException", NOW, Duration.ofMinutes(1));
		verify(translations, Mockito.never()).fail(eq(second.translationId()), any(Integer.class), any(), any(), any());
		verify(gateway, Mockito.times(1)).translateTitles(any());
	}

	@Test
	void translatesUncataloguedTitleWithAi() {
		DisclosureTitleTranslationRepository repository = Mockito.mock(DisclosureTitleTranslationRepository.class);
		DisclosureTitleTranslationCatalog catalog = Mockito.mock(DisclosureTitleTranslationCatalog.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationRepository translationRepository = Mockito.mock(TranslationRepository.class);
		UUID id = UUID.randomUUID();
		DisclosureTitleTranslationJob job = new DisclosureTitleTranslationJob(id, "source-hash", "신규 공시 제목", 1);
		TitleTranslationJob request = new TitleTranslationJob(
			id, job.sourceHash(), job.normalizedTitle(), DisclosureTitlePolicy.TRANSLATION_VERSION, 1
		);
		GeneratedTitle generated = new GeneratedTitle(
			id, job.sourceHash(), "New Disclosure Title", "en",
			DisclosureTitlePolicy.TRANSLATION_VERSION, "gpt-5-mini", "title-v1"
		);
		when(repository.claimJobs(eq(25), any(), eq(NOW), eq(NOW.minus(Duration.ofMinutes(5)))))
			.thenReturn(List.of(job));
		when(catalog.translate(job.normalizedTitle())).thenReturn(Optional.empty());
		when(gateway.translateTitles(List.of(request))).thenReturn(List.of(generated));

		new DisclosureTitleTranslationWorker(
			repository, catalog, gateway, translationRepository,
			Clock.fixed(NOW, ZoneOffset.UTC), "worker"
		).processBatch(25);

		verify(repository).complete(
			id, generated.translatedText(), generated.modelId(), generated.promptVersion(), NOW
		);
	}

	@Test
	void requeuesUncataloguedTitleWhenAiIsTemporarilyUnavailable() {
		DisclosureTitleTranslationRepository repository = Mockito.mock(DisclosureTitleTranslationRepository.class);
		DisclosureTitleTranslationCatalog catalog = Mockito.mock(DisclosureTitleTranslationCatalog.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationRepository translationRepository = Mockito.mock(TranslationRepository.class);
		UUID id = UUID.randomUUID();
		DisclosureTitleTranslationJob job = new DisclosureTitleTranslationJob(id, "source-hash", "신규 공시 제목", 1);
		when(repository.claimJobs(eq(25), any(), eq(NOW), eq(NOW.minus(Duration.ofMinutes(5)))))
			.thenReturn(List.of(job));
		when(catalog.translate(job.normalizedTitle())).thenReturn(Optional.empty());
		when(gateway.translateTitles(any())).thenThrow(new IllegalStateException("temporarily unavailable"));

		new DisclosureTitleTranslationWorker(
			repository, catalog, gateway, translationRepository,
			Clock.fixed(NOW, ZoneOffset.UTC), "worker"
		).processBatch(25);

		verify(translationRepository).fail(
			id, 1, "IllegalStateException", NOW, Duration.ofMinutes(1)
		);
	}
}
