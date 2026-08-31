package com.kmarket.navigator.backend.news.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.port.NewsProviderGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.CollectedNewsArticle;
import com.kmarket.navigator.backend.news.domain.NewsCollectionTarget;
import com.kmarket.navigator.backend.news.domain.NewsDraft;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import com.kmarket.navigator.backend.news.infrastructure.naver.NaverNewsProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class NewsCollectionService {

	private static final Logger log = LoggerFactory.getLogger(NewsCollectionService.class);
	private static final int DUPLICATE_CANDIDATE_LIMIT = 50_000;
	private final NewsProviderGateway provider;
	private final NewsRepository repository;
	private final NewsFingerprint fingerprint;
	private final NewsStockMatcher stockMatcher;
	private final NaverNewsProperties properties;
	private final Clock clock;

	@Autowired
	public NewsCollectionService(
		NewsProviderGateway provider,
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher,
		NaverNewsProperties properties
	) {
		this(provider, repository, fingerprint, stockMatcher, properties, Clock.systemUTC());
	}

	NewsCollectionService(
		NewsProviderGateway provider,
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher,
		NaverNewsProperties properties,
		Clock clock
	) {
		this.provider = provider;
		this.repository = repository;
		this.fingerprint = fingerprint;
		this.stockMatcher = stockMatcher;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.news.collection-interval:10m}",
		initialDelayString = "${kmarket.news.collection-initial-delay:30s}"
	)
	@SchedulerLock(name = "news-collection", lockAtMostFor = "PT9M", lockAtLeastFor = "PT1S")
	public void collect() {
		if (!provider.configured()) {
			return;
		}
		List<NewsStockMapping> mappings = repository.findStockMappings();
		NewsDuplicateIndex duplicateIndex = new NewsDuplicateIndex(fingerprint);
		repository.findDuplicateCandidates(
			Instant.now(clock).minus(Duration.ofHours(72)),
			DUPLICATE_CANDIDATE_LIMIT
		).forEach(candidate -> duplicateIndex.add(
			candidate.clusterId(),
			fingerprint.profile(candidate.title(), candidate.excerpt()),
			candidate.publishedAt(),
			candidate.publisher()
		));
		Map<String, String> queries = new LinkedHashMap<>();
		properties.getQueries().forEach(query -> queries.put(query, null));
		List<NewsCollectionTarget> targets = repository.findCollectionTargets(properties.getTargetBatchSize());
		targets.forEach(target -> queries.put(target.nameKo(), target.stockCode()));
		for (var queryEntry : queries.entrySet()) {
			String query = queryEntry.getKey();
			String queryStockCode = queryEntry.getValue();
			try {
				for (CollectedNewsArticle article : provider.search(query, properties.getDisplay())) {
					store(article, queryStockCode, mappings, duplicateIndex);
				}
				if (queryStockCode != null) {
					repository.markTargetCollected(queryStockCode, Instant.now(clock));
				}
			} catch (RuntimeException exception) {
				log.warn(
					"News collection failed for queryHash={} type={}",
					fingerprint.sha256(query),
					exception.getClass().getSimpleName()
				);
			}
			pause(properties.getRequestDelay());
		}
	}

	private void store(
		CollectedNewsArticle article,
		String queryStockCode,
		List<NewsStockMapping> mappings,
		NewsDuplicateIndex duplicateIndex
	) {
		Instant now = Instant.now(clock);
		if (article.publishedAt().isBefore(now.minus(properties.getMaxArticleAge()))
			|| article.publishedAt().isAfter(now.plus(Duration.ofMinutes(15)))) {
			return;
		}
		String canonicalUrl = fingerprint.canonicalizeUrl(article.canonicalUrl());
		String normalizedTitle = fingerprint.normalize(article.title());
		NewsFingerprint.Profile incoming = fingerprint.profile(article.title(), article.excerpt());
		NewsDuplicateIndex.Match duplicate = duplicateIndex.findBest(
			incoming,
			article.publishedAt(),
			article.publisher()
		);
		double bestScore = duplicate.score();
		UUID clusterId = duplicate.targetClusterId() != null
			? duplicate.targetClusterId()
			: UUID.randomUUID();
		Map<String, BigDecimal> stockMatches = stockMatcher.matchArticle(
			article.title(),
			article.excerpt(),
			mappings
		);
		if (stockMatches.isEmpty()
			|| (queryStockCode != null && !stockMatches.containsKey(queryStockCode))) {
			return;
		}
		if (duplicate.targetClusterId() != null) {
			repository.addClusterStockMappings(duplicate.targetClusterId(), stockMatches);
			return;
		}
		NewsDraft draft = new NewsDraft(
			UUID.randomUUID(),
			clusterId,
			fingerprint.sha256(
				normalizedTitle + ":" + article.publishedAt().atZone(ZoneOffset.UTC).toLocalDate()
			),
			normalizedTitle,
			article.providerArticleId(),
			article.title(),
			article.excerpt(),
			article.originalUrl(),
			canonicalUrl,
			fingerprint.sha256(canonicalUrl),
			article.publisher(),
			article.thumbnailUrl(),
			article.publishedAt(),
			now,
			BigDecimal.valueOf(bestScore),
			stockMatches
		);
		if (repository.saveCollected(draft)) {
			duplicateIndex.add(clusterId, incoming, article.publishedAt(), article.publisher());
		}
	}

	private void pause(Duration duration) {
		if (!duration.isNegative() && !duration.isZero()) {
			LockSupport.parkNanos(duration.toNanos());
		}
	}
}
