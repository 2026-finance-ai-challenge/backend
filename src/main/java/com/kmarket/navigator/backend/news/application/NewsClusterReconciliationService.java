package com.kmarket.navigator.backend.news.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
	@SchedulerLock(name = "news-cluster-reconciliation", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1S")
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
		List<NewsClusterAssignment> assignments = new ArrayList<>();
		long comparisons = 0;
		for (NewsDuplicateCandidate article : articles) {
			NewsFingerprint.Profile profile = fingerprint.profile(article.title(), article.excerpt());
			NewsDuplicateIndex.Match match = duplicateIndex.findBest(
				profile,
				article.publishedAt(),
				article.publisher()
			);
			comparisons += match.comparisons();
			UUID targetClusterId = match.targetClusterId() == null
				? article.clusterId()
				: match.targetClusterId();
			if (!targetClusterId.equals(article.clusterId())) {
				assignments.add(new NewsClusterAssignment(article.articleId(), targetClusterId));
			}
			duplicateIndex.add(targetClusterId, profile, article.publishedAt(), article.publisher());
		}
		int updated = repository.replaceClusterAssignments(assignments, now);
		log.info(
			"News cluster reconciliation completed articles={} comparisons={} assignments={} updated={}",
			articles.size(), comparisons, assignments.size(), updated
		);
	}
}
