package com.kmarket.navigator.backend.news.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsClusterAssignment;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class NewsClusterReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(NewsClusterReconciliationService.class);
	private static final int ARTICLE_LIMIT = 50_000;
	private final NewsRepository repository;
	private final NewsFingerprint fingerprint;
	private final Clock clock;

	@Autowired
	public NewsClusterReconciliationService(NewsRepository repository, NewsFingerprint fingerprint) {
		this(repository, fingerprint, Clock.systemUTC());
	}

	NewsClusterReconciliationService(
		NewsRepository repository,
		NewsFingerprint fingerprint,
		Clock clock
	) {
		this.repository = repository;
		this.fingerprint = fingerprint;
		this.clock = clock;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.news.reconciliation-interval:6h}",
		initialDelayString = "${kmarket.news.reconciliation-initial-delay:1m}"
	)
	@SchedulerLock(name = "news-collection", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1S")
	public void reconcile() {
		Instant now = Instant.now(clock);
		List<NewsDuplicateCandidate> articles = new ArrayList<>(repository.findDuplicateCandidates(
			now.minus(Duration.ofHours(72)),
			ARTICLE_LIMIT
		));
		articles.sort(Comparator
			.comparing(NewsDuplicateCandidate::publishedAt)
			.thenComparing(NewsDuplicateCandidate::articleId));
		NewsDuplicateIndex duplicateIndex = new NewsDuplicateIndex(fingerprint);
		var mappings = repository.findStockMappings();
		var matcher = new NewsStockMatcher();
		ClusterUnion clusters = new ClusterUnion();
		articles.forEach(article -> clusters.add(article.clusterId()));
		long comparisons = 0;
		for (NewsDuplicateCandidate article : articles) {
			var stocks = matcher.verifiedArticleMatches(article.title(), article.body(), mappings).keySet();
			if (stocks.isEmpty() && article.body() != null && !article.body().isBlank()) continue;
			NewsFingerprint.Profile profile = fingerprint.profile(article.title(), article.excerpt(), article.body());
			NewsDuplicateIndex.Match match = duplicateIndex.findBest(
				profile,
				article.publishedAt(),
				article.publisher(), stocks
			);
			comparisons += match.comparisons();
			if (match.targetClusterId() != null) {
				clusters.mergeInto(article.clusterId(), match.targetClusterId());
			}
			UUID targetClusterId = clusters.find(article.clusterId());
			duplicateIndex.add(targetClusterId, profile, article.publishedAt(), article.publisher(), stocks);
		}
		List<NewsClusterAssignment> assignments = articles.stream()
			.filter(article -> !article.clusterId().equals(clusters.find(article.clusterId())))
			.map(article -> new NewsClusterAssignment(
				article.articleId(),
				clusters.find(article.clusterId())
			))
			.toList();
		int updated = repository.replaceClusterAssignments(assignments, now);
		log.info(
			"News cluster reconciliation completed articles={} comparisons={} assignments={} updated={}",
			articles.size(), comparisons, assignments.size(), updated
		);
	}

	private static final class ClusterUnion {

		private final Map<UUID, UUID> parent = new HashMap<>();

		void add(UUID clusterId) {
			parent.putIfAbsent(clusterId, clusterId);
		}

		UUID find(UUID clusterId) {
			add(clusterId);
			UUID root = parent.get(clusterId);
			if (!root.equals(clusterId)) {
				root = find(root);
				parent.put(clusterId, root);
			}
			return root;
		}

		void mergeInto(UUID sourceClusterId, UUID targetClusterId) {
			UUID sourceRoot = find(sourceClusterId);
			UUID targetRoot = find(targetClusterId);
			if (!sourceRoot.equals(targetRoot)) {
				parent.put(sourceRoot, targetRoot);
			}
		}
	}
}
