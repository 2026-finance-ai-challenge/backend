package com.kmarket.navigator.backend.news.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kmarket.navigator.backend.news.application.port.NewsProviderGateway;
import com.kmarket.navigator.backend.news.application.port.NewsOriginalArticleGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.CollectedNewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsCollectionTarget;
import com.kmarket.navigator.backend.news.domain.NewsDraft;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import com.kmarket.navigator.backend.news.domain.OriginalNewsArticle;
import com.kmarket.navigator.backend.news.infrastructure.naver.NaverNewsProperties;

class NewsCollectionServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-31T02:00:00Z");
	private final NewsProviderGateway provider = mock(NewsProviderGateway.class);
	private final NewsOriginalArticleGateway originalArticleGateway = mock(NewsOriginalArticleGateway.class);
	private final NewsRepository repository = mock(NewsRepository.class);
	private final NewsFingerprint fingerprint = new NewsFingerprint();
	private final NewsStockMatcher stockMatcher = new NewsStockMatcher();
	private final NaverNewsProperties properties = new NaverNewsProperties();
	private NewsCollectionService service;

	@BeforeEach
	void setUp() {
		properties.setQueries(List.of());
		properties.setRequestDelay(java.time.Duration.ZERO);
		properties.setMaxArticleAge(java.time.Duration.ofHours(72));
		when(provider.configured()).thenReturn(true);
		when(repository.findStockMappings()).thenReturn(mappings());
		when(repository.findCollectionTargets(anyInt())).thenReturn(List.of(
			new NewsCollectionTarget("005930", "삼성전자", "Samsung Electronics")
		));
		when(repository.findDuplicateCandidates(any(), anyInt())).thenReturn(List.of());
		service = new NewsCollectionService(
			provider,
			originalArticleGateway,
			repository,
			fingerprint,
			stockMatcher,
			properties,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void skipsSearchResultThatDoesNotExplicitlyMentionQueriedStock() {
		when(provider.search(eq("삼성전자"), anyInt())).thenReturn(List.of(article(
			"SK하이닉스가 HBM 신규 공장을 착공했다",
			"SK하이닉스는 신규 생산라인 투자를 시작한다고 발표했다.",
			"other.example.com"
		)));

		service.collect();

		verify(repository, never()).saveCollected(any(NewsDraft.class));
		verify(repository, never()).addClusterStockMappings(any(), any());
	}

	@Test
	void skipsDuplicateBeforeTranslationAndAnalysisAreQueued() {
		UUID clusterId = UUID.randomUUID();
		String excerpt = "삼성전자는 평택 반도체 생산라인에 신규 투자를 집행하고 차세대 HBM 생산능력을 확대한다고 발표했다.";
		when(repository.findDuplicateCandidates(any(), anyInt())).thenReturn(List.of(
			new NewsDuplicateCandidate(
				UUID.randomUUID(),
				clusterId,
				"삼성전자 평택 HBM 생산라인 투자 확대",
				excerpt,
				"first.example.com",
				NOW.minusSeconds(600)
			)
		));
		when(provider.search(eq("삼성전자"), anyInt())).thenReturn(List.of(article(
			"삼성전자 차세대 HBM 생산능력 확대",
			excerpt,
			"second.example.com"
		)));
		when(originalArticleGateway.fetch(any())).thenReturn(java.util.Optional.of(
			new OriginalNewsArticle(excerpt, "https://second.example.com/second-example-com", null,
				"publisher_public_article_v1")
		));

		service.collect();

		verify(repository, never()).saveCollected(any(NewsDraft.class));
		verify(repository).addClusterStockMappings(eq(clusterId), any());
	}

	@Test
	void storesOnlyHeadlineMatchesAndPreservesProviderExcerpt() {
		String excerpt = "삼성전자가 차세대 반도체 투자 계획을 발표했다.";
		when(provider.search(eq("삼성전자"), anyInt())).thenReturn(List.of(article(
			"삼성전자, 차세대 반도체 투자 확대",
			excerpt,
			"news.example.com"
		)));
		when(originalArticleGateway.fetch(any())).thenReturn(java.util.Optional.of(
			new OriginalNewsArticle(
				"본문\n\n관련 기사: SK하이닉스와 기타 지원 종목 목록",
				"https://news.example.com/article",
				null,
				"publisher_public_article_v1"
			)
		));

		service.collect();

		ArgumentCaptor<NewsDraft> draft = ArgumentCaptor.forClass(NewsDraft.class);
		verify(repository).saveCollected(draft.capture());
		org.assertj.core.api.Assertions.assertThat(draft.getValue().excerpt()).isEqualTo(excerpt);
		org.assertj.core.api.Assertions.assertThat(draft.getValue().stockConfidences().keySet())
			.containsExactly("005930");
	}

	private List<NewsStockMapping> mappings() {
		return List.of(
			new NewsStockMapping(
				"005930", "삼성전자", "Samsung Electronics", "KOSPI",
				List.of("005930", "삼성전자", "Samsung Electronics")
			),
			new NewsStockMapping(
				"000660", "SK하이닉스", "SK hynix", "KOSPI",
				List.of("000660", "SK하이닉스", "SK hynix")
			)
		);
	}

	private CollectedNewsArticle article(String title, String excerpt, String publisher) {
		String slug = publisher.replace('.', '-');
		return new CollectedNewsArticle(
			slug,
			title,
			excerpt,
			"https://" + publisher + "/" + slug,
			"https://" + publisher + "/" + slug,
			publisher,
			null,
			NOW.minusSeconds(300)
		);
	}
}
