package com.kmarket.navigator.backend.translation.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
	void persistsRemainingTitlesWithoutRegeneratingAfterOneDatabaseFailure() {
		TranslationRepository repository = Mockito.mock(TranslationRepository.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationGenerationGuard guard = Mockito.mock(TranslationGenerationGuard.class);
		var jobs = java.util.stream.IntStream.range(0, 3).mapToObj(index -> new TitleTranslationJob(
			UUID.randomUUID(), "%064d".formatted(index), "기사 제목 " + index, "news-title-v3", 1
		)).toList();
		var generated = jobs.stream().map(job -> new GeneratedTitle(
			job.id(), job.sourceHash(), "Jo discusses the earnings outlook", "en",
			job.translationVersion(), "gpt-5-nano", "financial-title-translation-v8"
		)).toList();
		when(repository.claimNewsTitles(eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(jobs);
		when(gateway.translateTitles(jobs)).thenReturn(generated);
		doThrow(new org.springframework.dao.DataIntegrityViolationException("constraint"))
			.when(repository).completeNewsTitle(generated.get(1), NOW);

		new TranslationWorker(repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)).processTitleBatch();

		for (var title : generated) verify(repository).completeNewsTitle(title, NOW);
		verify(repository).fail(jobs.get(1).id(), 1, "DataIntegrityViolationException", NOW, Duration.ofMinutes(15));
		for (int index : new int[]{0, 2}) {
			verify(repository, never()).fail(eq(jobs.get(index).id()), eq(1), anyString(), eq(NOW),
				org.mockito.ArgumentMatchers.any(Duration.class));
		}
		verify(gateway, times(1)).translateTitles(jobs);
	}

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
		when(repository.claimNewsTitles(eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job))).thenReturn(List.of(generated));

		new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		).processTitleBatch();

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
		when(repository.claimNewsTitles(eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
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

		worker.processTitleBatch();
		worker.processTitleBatch();

		verify(repository, times(1)).claimNewsTitles(
			eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))
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
		when(repository.claimNewsTitles(eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(job));
		when(repository.claim(eq(10), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of());
		when(gateway.translateTitles(List.of(job)))
			.thenThrow(new TranslationProviderException(TranslationProviderException.Failure.TIMEOUT));
		TranslationWorker worker = new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		);

		worker.processTitleBatch();
		worker.processTitleBatch();

		verify(repository, times(1)).claimNewsTitles(
			eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))
		);
		verify(repository).fail(
			eq(job.id()), eq(1), eq("AI_PROVIDER_TIMEOUT"), eq(NOW), eq(Duration.ofSeconds(15))
		);
	}

	@Test
	void rejectsInvalidTitleBatchWithoutAdditionalProviderCalls() {
		TranslationRepository repository = Mockito.mock(TranslationRepository.class);
		TranslationAiGateway gateway = Mockito.mock(TranslationAiGateway.class);
		TranslationGenerationGuard guard = Mockito.mock(TranslationGenerationGuard.class);
		TitleTranslationJob valid = new TitleTranslationJob(
			UUID.randomUUID(),
			"4bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359677",
			"삼성전자 투자 확대", "news-title-v1", 1
		);
		TitleTranslationJob invalid = new TitleTranslationJob(
			UUID.randomUUID(),
			"5bf85830b94228184e8234c14e92c8c9eee79847867458ba624b29d3ce359678",
			"목표가 240만원", "news-title-v1", 1
		);
		GeneratedTitle generated = new GeneratedTitle(
			valid.id(), valid.sourceHash(), "Samsung Electronics expands investment", "en",
			"news-title-v1", "gpt-5-mini", "news-title-v1"
		);
		when(repository.claimNewsTitles(eq(5), anyString(), eq(NOW), eq(NOW.minusSeconds(300))))
			.thenReturn(List.of(valid, invalid));
		when(gateway.translateTitles(List.of(valid, invalid)))
			.thenThrow(new TranslationProviderException(
				TranslationProviderException.Failure.INVALID_OUTPUT
			));
		new TranslationWorker(
			repository, gateway, guard, JsonMapper.builder().build(),
			Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15)
		).processTitleBatch();

		verify(gateway, times(1)).translateTitles(List.of(valid, invalid));
		verify(repository).fail(
			eq(valid.id()), eq(1), eq("AI_INVALID_OUTPUT"), eq(NOW), eq(Duration.ZERO)
		);
		verify(repository).fail(
			eq(invalid.id()), eq(1), eq("AI_INVALID_OUTPUT"), eq(NOW), eq(Duration.ZERO)
		);
	}
}
