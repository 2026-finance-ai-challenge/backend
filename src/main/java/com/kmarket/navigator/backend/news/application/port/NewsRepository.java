package com.kmarket.navigator.backend.news.application.port;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.kmarket.navigator.backend.news.domain.NewsAnalysis;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisJob;
import com.kmarket.navigator.backend.news.domain.NewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsCollectionTarget;
import com.kmarket.navigator.backend.news.domain.NewsClusterAssignment;
import com.kmarket.navigator.backend.news.domain.NewsDraft;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsPage;
import com.kmarket.navigator.backend.news.domain.NewsQuery;
import com.kmarket.navigator.backend.news.domain.NewsRetention;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import com.kmarket.navigator.backend.news.domain.TermReference;

public interface NewsRepository {

	NewsPage findPage(NewsQuery query);

	Optional<NewsArticle> findById(UUID articleId);

	List<UUID> findNarrativeBackfillCandidates(int limit);

	List<NewsDuplicateCandidate> findDuplicateCandidates(Instant since, int limit);

	int replaceClusterAssignments(List<NewsClusterAssignment> assignments, Instant reconciledAt);

	List<NewsStockMapping> findStockMappings();

	List<NewsCollectionTarget> findCollectionTargets(int limit);

	void markTargetCollected(String stockCode, Instant collectedAt);

	boolean saveCollected(NewsDraft draft);

	void addClusterStockMappings(UUID clusterId, Map<String, BigDecimal> stockConfidences);

	boolean newsMaintenanceApplied(String version);

	void applyNewsMaintenance(
		String version,
		List<NewsRetention> retained,
		List<UUID> deletedArticleIds,
		Instant appliedAt
	);

	List<NewsAnalysisJob> claimAnalysisJobs(int limit, Instant now);

	void completeAnalysis(UUID articleId, NewsAnalysis analysis, Instant analyzedAt);

	void failAnalysis(UUID articleId, int attempts, String errorCode, Instant now, Duration retryDelay);

	List<TermReference> findTermReferences(String selectedText, int limit);

	void recordExplanationClick(
		UUID articleId,
		UUID userId,
		String selectedTextHash,
		String clientIpHash,
		Instant clickedAt
	);
}
