package com.kmarket.navigator.backend.news.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.kmarket.navigator.backend.news.domain.NewsDuplicateCandidate;
import com.kmarket.navigator.backend.news.domain.NewsStockMapping;
import com.kmarket.navigator.backend.news.infrastructure.naver.NaverNewsProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class NewsCollectionService {

	private static final Logger log = LoggerFactory.getLogger(NewsCollectionService.class);
	private static final double DUPLICATE_THRESHOLD = 0.82;
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
		List<NewsDuplicateCandidate> candidates = new ArrayList<>(repository.findDuplicateCandidates(
			Instant.now(clock).minus(Duration.ofHours(72)),
			2_000
		));
		Set<String> queries = new LinkedHashSet<>(properties.getQueries());
		List<NewsCollectionTarget> targets = repository.findCollectionTargets(properties.getTargetBatchSize());
		targets.forEach(target -> queries.add(target.nameKo()));
		for (String query : queries) {
			String queryStockCode = targets.stream()
				.filter(target -> target.nameKo().equals(query))
				.map(NewsCollectionTarget::stockCode)
				.findFirst()
				.orElse(null);
			try {
				for (CollectedNewsArticle article : provider.search(query, properties.getDisplay())) {
					store(article, queryStockCode, mappings, candidates);
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
		List<NewsDuplicateCandidate> candidates
	) {
		String canonicalUrl = fingerprint.canonicalizeUrl(article.canonicalUrl());
		String normalizedTitle = fingerprint.normalize(article.title());
		String comparableText = normalizedTitle + " " + fingerprint.normalize(article.excerpt());
		NewsDuplicateCandidate duplicate = null;
		double bestScore = 0;
		for (NewsDuplicateCandidate candidate : candidates) {
			double score = fingerprint.similarity(comparableText, candidate.comparableText());
			if (score > bestScore) {
				bestScore = score;
				duplicate = candidate;
			}
		}
		UUID clusterId = bestScore >= DUPLICATE_THRESHOLD && duplicate != null
			? duplicate.clusterId()
			: UUID.randomUUID();
		Map<String, BigDecimal> stockMatches = stockMatcher.match(
			article.title() + " " + article.excerpt(),
			mappings,
			queryStockCode
		);
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
			Instant.now(clock),
			BigDecimal.valueOf(bestScore),
			stockMatches
		);
		if (repository.saveCollected(draft) && bestScore < DUPLICATE_THRESHOLD) {
			candidates.add(new NewsDuplicateCandidate(clusterId, comparableText));
		}
	}

	private void pause(Duration duration) {
		if (!duration.isNegative() && !duration.isZero()) {
			LockSupport.parkNanos(duration.toNanos());
		}
	}
}
