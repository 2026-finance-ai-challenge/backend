package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsRetention;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;

class NewsDataMaintenanceServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");

	@Test
	void retainsLatestRelevantStoryAndDeletesDuplicateAndIrrelevantArticles() {
		NewsRepository repository = mock(NewsRepository.class);
		NewsFingerprint fingerprint = new NewsFingerprint();
		NewsStockMatcher matcher = new NewsStockMatcher();
		UUID latest = UUID.randomUUID();
		UUID duplicate = UUID.randomUUID();
		UUID irrelevant = UUID.randomUUID();
		String excerpt = "삼성전자는 평택 반도체 생산라인에 대규모 신규 투자를 집행하고 차세대 HBM 생산능력을 확대한다고 발표했다. 회사는 고객 수요에 맞춰 설비를 단계적으로 가동할 계획이다.";
		when(repository.newsMaintenanceApplied(anyString())).thenReturn(false);
		when(repository.findDuplicateCandidates(any(), anyInt())).thenReturn(List.of(
			candidate(latest, "삼성전자 차세대 HBM 생산능력 확대", excerpt, "new.example.com", 60),
			candidate(duplicate, "삼성전자 평택 반도체 신규 투자", excerpt, "old.example.com", 600),
			candidate(irrelevant, "유럽 축구 리그 결승전 결과", "우승팀이 연장전 끝에 승리했다.", "sport.example.com", 120)
		));
		when(repository.findStockMappings()).thenReturn(List.of(new NewsStockMapping(
			"005930", "삼성전자", "Samsung Electronics", "KOSPI",
			List.of("005930", "삼성전자", "Samsung Electronics")
		)));
		var service = new NewsDataMaintenanceService(
			repository,
			fingerprint,
			matcher,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);

		service.maintain();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NewsRetention>> retainedCaptor = ArgumentCaptor.forClass(List.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<UUID>> deletedCaptor = ArgumentCaptor.forClass(List.class);
		verify(repository).applyNewsMaintenance(
			anyString(),
			retainedCaptor.capture(),
			deletedCaptor.capture(),
			any()
		);
		assertThat(retainedCaptor.getValue())
			.extracting(NewsRetention::articleId)
			.containsExactly(latest);
		assertThat(deletedCaptor.getValue()).containsExactlyInAnyOrder(duplicate, irrelevant);
	}

	@Test
	void doesNothingAfterMaintenanceVersionWasApplied() {
		NewsRepository repository = mock(NewsRepository.class);
		when(repository.newsMaintenanceApplied(anyString())).thenReturn(true);
		var service = new NewsDataMaintenanceService(
			repository,
			new NewsFingerprint(),
			new NewsStockMatcher(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);

		service.maintain();

		verify(repository, never()).findDuplicateCandidates(any(), anyInt());
	}

	private NewsDuplicateCandidate candidate(
		UUID articleId,
		String title,
		String excerpt,
		String publisher,
		long ageSeconds
	) {
		return new NewsDuplicateCandidate(
			articleId,
			UUID.randomUUID(),
			title,
			excerpt,
			publisher,
			NOW.minusSeconds(ageSeconds)
		);
	}
}
