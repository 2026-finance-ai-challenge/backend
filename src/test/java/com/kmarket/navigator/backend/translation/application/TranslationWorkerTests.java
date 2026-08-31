package com.kmarket.navigator.backend.translation.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
		when(repository.claimNewsTitles(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job))).thenReturn(List.of(generated));

		new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		).process();

		verify(repository).completeNewsTitle(generated, NOW);
	}

	@Test
	void pausesTitleClaimsAfterProviderQuotaExhaustion() {
		TranslationRepository repository = Mockito.mock(TranslationRepository.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationGenerationGuard guard = Mockito.mock(TranslationGenerationGuard.class);
		TitleTranslationJob job = new TitleTranslationJob(
			UUID.randomUUID(),
			"4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
			"삼성전자 투자 확대", "news-title-v1", 1
		);
		when(repository.claimNewsTitles(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job)))
			.thenThrow(new TranslationProviderException(
				TranslationProviderException.Failure.QUOTA_EXHAUSTED
			));
		TranslationWorker worker = new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		);

		worker.process();
		worker.process();

		verify(repository, times(1)).claimNewsTitles(
			eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))
		);
		verify(repository).fail(
			eq(job.id()), eq(1), eq("AI_PROVIDER_QUOTA_EXHAUSTED"), eq(NOW),
			eq(Duration.ofMinutes(15))
		);
	}

	@Test
	void retriesTitleClaimsQuicklyAfterProviderTimeout() {
		TranslationRepository repository = Mockito.mock(TranslationRepository.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationGenerationGuard guard = Mockito.mock(TranslationGenerationGuard.class);
		TitleTranslationJob job = new TitleTranslationJob(
			UUID.randomUUID(),
			"4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
			"삼성전자 투자 확대", "news-title-v1", 1
		);
		when(repository.claimNewsTitles(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job)))
			.thenThrow(new TranslationProviderException(TranslationProviderException.Failure.TIMEOUT));
		TranslationWorker worker = new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		);

		worker.process();
		worker.process();

		verify(repository, times(1)).claimNewsTitles(
			eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))
		);
		verify(repository).fail(
			eq(job.id()), eq(1), eq("AI_PROVIDER_TIMEOUT"), eq(NOW), eq(Duration.ofSeconds(30))
		);
	}
}
