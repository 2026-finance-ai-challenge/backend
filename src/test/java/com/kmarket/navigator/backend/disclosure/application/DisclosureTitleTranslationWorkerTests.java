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
