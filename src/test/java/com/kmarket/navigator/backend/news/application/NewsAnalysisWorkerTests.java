package com.kmarket.navigator.backend.news.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.MarketImpact;
import com.kmarket.navigator.backend.news.domain.NewsAnalysis;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisJob;
import com.kmarket.navigator.backend.news.domain.NewsImportance;
import com.kmarket.navigator.backend.news.domain.NewsSentiment;

class NewsAnalysisWorkerTests {

	private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

	@Test
	void completesClaimedAnalysisWithStructuredResult() {
		NewsRepository repository = Mockito.mock(NewsRepository.class);
		NewsAiGateway gateway = Mockito.mock(NewsAiGateway.class);
		UUID articleId = UUID.randomUUID();
		NewsAnalysisJob job = new NewsAnalysisJob(
			articleId,
			"삼성전자 투자 확대",
			List.of("삼성전자가 신규 투자를 발표했다."),
			List.of("삼성전자", "Samsung Electronics"),
			1
		);
		NewsAnalysis analysis = analysis();
		when(repository.claimAnalysisJobs(10, NOW)).thenReturn(List.of(job));
		when(gateway.analyze(job.title(), job.paragraphs(), job.candidateCompanies()))
			.thenReturn(analysis);

		new NewsAnalysisWorker(repository, gateway, Clock.fixed(NOW, ZoneOffset.UTC)).process();

		verify(repository).completeAnalysis(articleId, analysis, NOW);
	}

	@Test
	void schedulesBoundedRetryWithoutLosingArticle() {
		NewsRepository repository = Mockito.mock(NewsRepository.class);
		NewsAiGateway gateway = Mockito.mock(NewsAiGateway.class);
		UUID articleId = UUID.randomUUID();
		NewsAnalysisJob job = new NewsAnalysisJob(
			articleId,
			"제목",
			List.of("본문"),
			List.of(),
			2
		);
		when(repository.claimAnalysisJobs(10, NOW)).thenReturn(List.of(job));
		when(gateway.analyze(any(), any(), any())).thenThrow(new IllegalStateException("temporary"));

		new NewsAnalysisWorker(repository, gateway, Clock.fixed(NOW, ZoneOffset.UTC)).process();

		verify(repository).failAnalysis(
			eq(articleId),
			eq(2),
			eq("IllegalStateException"),
			eq(NOW),
			eq(Duration.ofSeconds(120))
		);
	}

	private static NewsAnalysis analysis() {
		return new NewsAnalysis(
			"Samsung Electronics expands investment",
			List.of("Samsung Electronics announced a new investment."),
			"The company announced an investment.",
			"It is expanding production capacity.",
			"The plan may increase future capacity.",
			"CAPEX",
			NewsSentiment.POSITIVE,
			NewsImportance.HIGH,
			MarketImpact.POSITIVE,
			new BigDecimal("0.90"),
			new BigDecimal("0.80"),
			new BigDecimal("0.85"),
			new BigDecimal("0.75"),
			"gpt-5-mini",
			"news-analysis-v1"
		);
	}
}
