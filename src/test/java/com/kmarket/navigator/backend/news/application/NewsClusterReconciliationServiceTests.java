package com.kmarket.navigator.backend.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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
import com.kmarket.navigator.backend.news.domain.NewsClusterAssignment;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;

class NewsClusterReconciliationServiceTests {

	@Test
	void mergesCrossPublisherStoryAndKeepsUnrelatedBoilerplateSeparate() {
		NewsRepository repository = mock(NewsRepository.class);
		NewsFingerprint fingerprint = new NewsFingerprint();
		Instant now = Instant.parse("2026-08-29T00:00:00Z");
		UUID firstCluster = UUID.randomUUID();
		UUID duplicateCluster = UUID.randomUUID();
		UUID unrelatedCluster = UUID.randomUUID();
		UUID firstArticle = UUID.randomUUID();
		UUID duplicateArticle = UUID.randomUUID();
		UUID unrelatedArticle = UUID.randomUUID();
		String boilerplate = "아래는 위 기사를 번역한 영문 기사입니다 원본 기사 보기와 기자 연락처가 포함됩니다";
		when(repository.findDuplicateCandidates(any(), anyInt())).thenReturn(List.of(
			new NewsDuplicateCandidate(
				firstArticle,
				firstCluster,
				"'네팔·중국 대홍수' 사망자 584명…실종자 2,500명 육박",
				"네팔과 중국의 대규모 홍수로 사망자가 584명으로 늘고 실종자가 2500명에 육박했다.",
				now.minusSeconds(3_600)
			),
			new NewsDuplicateCandidate(
				duplicateArticle,
				duplicateCluster,
				"네팔 대홍수 사망자 584명…실종자 2천500명 육박",
				"대홍수 피해가 이어져 사망자 584명과 실종자 약 2500명이 집계됐다.",
				now.minusSeconds(3_000)
			),
			new NewsDuplicateCandidate(
				unrelatedArticle,
				unrelatedCluster,
				"기업 실적 악화 구조조정 검토",
				boilerplate,
				now.minusSeconds(2_000)
			)
		));
		when(repository.replaceClusterAssignments(any(), any())).thenAnswer(invocation ->
			((List<?>)invocation.getArgument(0)).size()
		);
		var service = new NewsClusterReconciliationService(
			repository,
			fingerprint,
			Clock.fixed(now, ZoneOffset.UTC)
		);

		service.reconcile();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<NewsClusterAssignment>> captor = ArgumentCaptor.forClass(List.class);
		verify(repository).replaceClusterAssignments(captor.capture(), any());
		assertThat(captor.getValue())
			.containsExactly(new NewsClusterAssignment(duplicateArticle, firstCluster));
	}
}
