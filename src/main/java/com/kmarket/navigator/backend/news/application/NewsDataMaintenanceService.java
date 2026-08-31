package com.kmarket.navigator.backend.news.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsRetention;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
@ConditionalOnProperty(prefix = "kmarket.news", name = "maintenance-enabled", havingValue = "true")
public class NewsDataMaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(NewsDataMaintenanceService.class);
	static final String VERSION = "news-relevance-dedup-v3";
	private static final int ARTICLE_LIMIT = 100_000;
	private final NewsRepository repository;
	private final NewsFingerprint fingerprint;
	private final NewsStockMatcher stockMatcher;
	private final Clock clock;

	@Autowired
	public NewsDataMaintenanceService(
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher
	) {
		this(repository, fingerprint, stockMatcher, Clock.systemUTC());
	}

	NewsDataMaintenanceService(
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher,
		Clock clock
	) {
		this.repository = repository;
		this.fingerprint = fingerprint;
		this.stockMatcher = stockMatcher;
		this.clock = clock;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.news.maintenance-interval:1h}",
		initialDelayString = "${kmarket.news.maintenance-initial-delay:30s}"
	)
	@SchedulerLock(name = "news-collection", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1S")
	public void maintain() {
		if (repository.newsMaintenanceApplied(VERSION)) {
			return;
		}
		List<NewsDuplicateCandidate> candidates = new ArrayList<>(
			repository.findDuplicateCandidates(Instant.EPOCH, ARTICLE_LIMIT)
		);
		if (candidates.size() >= ARTICLE_LIMIT) {
			log.error("News maintenance stopped because article limit was reached limit={}", ARTICLE_LIMIT);
			return;
		}
		candidates.sort(Comparator
			.comparing(NewsDuplicateCandidate::publishedAt)
			.reversed()
			.thenComparing(NewsDuplicateCandidate::articleId));
		List<NewsRetention> retained = new ArrayList<>();
		List<UUID> deleted = new ArrayList<>();
		NewsDuplicateIndex duplicateIndex = new NewsDuplicateIndex(fingerprint);
		var mappings = repository.findStockMappings();
		for (NewsDuplicateCandidate candidate : candidates) {
			var stockMatches = stockMatcher.matchArticle(
				candidate.title(),
				candidate.excerpt(),
				mappings
			);
			if (stockMatches.isEmpty()) {
				deleted.add(candidate.articleId());
				continue;
			}
			var profile = fingerprint.profile(candidate.title(), candidate.excerpt());
			var duplicate = duplicateIndex.findBest(
				profile,
				candidate.publishedAt(),
				candidate.publisher()
			);
			if (duplicate.targetClusterId() != null) {
				deleted.add(candidate.articleId());
				continue;
			}
			UUID clusterId = UUID.randomUUID();
			retained.add(new NewsRetention(
				candidate.articleId(),
				clusterId,
				fingerprint.sha256(VERSION + ":" + candidate.articleId()),
				fingerprint.normalize(candidate.title()),
				stockMatches
			));
			duplicateIndex.add(clusterId, profile, candidate.publishedAt(), candidate.publisher());
		}
		Instant now = Instant.now(clock);
		repository.applyNewsMaintenance(VERSION, retained, deleted, now);
		log.info(
			"News maintenance completed version={} retained={} deleted={}",
			VERSION, retained.size(), deleted.size()
		);
	}
}
