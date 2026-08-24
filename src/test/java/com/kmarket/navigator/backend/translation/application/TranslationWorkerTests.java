package com.kmarket.navigator.backend.translation.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.kmarket.navigator.backend.translation.application.port.TranslationAiGateway;
import com.kmarket.navigator.backend.translation.application.port.TranslationRepository;
import com.kmarket.navigator.backend.translation.domain.GeneratedTitle;
import com.kmarket.navigator.backend.translation.domain.TitleTranslationJob;

import tools.jackson.databind.json.JsonMapper;

class TranslationWorkerTests {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

	@Test
	void translatesClaimedNewsTitlesAsOneValidatedBatch() {
		TranslationRepository repository = Mockito.mock(TranslationRepository.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationGenerationGuard guard = Mockito.mock(TranslationGenerationGuard.class);
		UUID id = UUID.randomUUID();
		TitleTranslationJob job = new TitleTranslationJob(
			id,
			"4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
			"삼성전자 투자 확대",
			"news-title-v1",
			1
		);
		GeneratedTitle generated = new GeneratedTitle(
			id, job.sourceHash(), "Samsung Electronics expands investment", "en",
			"news-title-v1", "gpt-5-mini", "news-title-v1"
		);
		when(repository.claimNewsTitles(eq(25), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job))).thenReturn(List.of(generated));

		new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		).process();

		verify(repository).completeNewsTitle(generated, NOW);
	}
}
