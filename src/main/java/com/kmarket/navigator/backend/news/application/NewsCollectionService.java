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
import java.util.concurrent.ConcurrentHashMap;
import com.kmarket.navigator.backend.global.concurrent.BoundedTasks;
import com.kmarket.navigator.backend.news.domain.OriginalNewsArticle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.port.NewsOriginalArticleGateway;
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
	private final NewsOriginalArticleGateway originalArticleGateway;
	private final NewsRepository repository;
	private final NewsFingerprint fingerprint;
	private final NewsStockMatcher stockMatcher;
	private final NaverNewsProperties properties;
	private final Clock clock;

	@Autowired
	public NewsCollectionService(
		NewsProviderGateway provider,
		NewsOriginalArticleGateway originalArticleGateway,
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher,
		NaverNewsProperties properties
	) {
		this(provider, originalArticleGateway, repository, fingerprint, stockMatcher, properties, Clock.systemUTC());
	}

	NewsCollectionService(
		NewsProviderGateway provider,
		NewsOriginalArticleGateway originalArticleGateway,
		NewsRepository repository,
		NewsFingerprint fingerprint,
		NewsStockMatcher stockMatcher,
		NaverNewsProperties properties,
		Clock clock
	) {
		this.provider = provider;
		this.originalArticleGateway = originalArticleGateway;
		this.repository = repository;
		this.fingerprint = fingerprint;
		this.stockMatcher = stockMatcher;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.news.collection-interval:2m}",
		initialDelayString = "${kmarket.news.collection-initial-delay:30s}"
	)
	@SchedulerLock(name = "news-collection", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1S")
	public void collect() {
		if (!provider.configured()) {
			return;
		}
		long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
		List<NewsStockMapping> mappings = repository.findStockMappings();
		var collectedIds = new java.util.HashSet<>(repository.findCollectedProviderIds(Instant.now(clock).minus(Duration.ofHours(72))));
		NewsDuplicateIndex duplicateIndex = new NewsDuplicateIndex(fingerprint);
		repository.findDuplicateCandidates(
			Instant.now(clock).minus(Duration.ofHours(72)),
			DUPLICATE_CANDIDATE_LIMIT
		).stream().filter(candidate -> candidate.body() == null || candidate.body().isBlank()
			|| !stockMatcher.verifiedArticleMatches(candidate.title(), candidate.body(), mappings).isEmpty())
		.forEach(candidate -> duplicateIndex.add(
			candidate.clusterId(),
			fingerprint.profile(candidate.title(), candidate.excerpt(), candidate.body()),
			candidate.publishedAt(),
			candidate.publisher(),
			(candidate.body() == null || candidate.body().isBlank()
				? stockMatcher.matchArticle(candidate.title(), "", mappings)
				: stockMatcher.verifiedArticleMatches(candidate.title(), candidate.body(), mappings)).keySet()
		));
		Map<String, String> queries = new LinkedHashMap<>();
		properties.getQueries().forEach(query -> queries.put(query, null));
		List<NewsCollectionTarget> targets = repository.findCollectionTargets(properties.getTargetBatchSize());
		targets.forEach(target -> queries.put(target.nameKo(), target.stockCode()));
		for (var queryEntry : queries.entrySet()) {
			if (System.nanoTime() >= deadline) break;
			String query = queryEntry.getKey();
			String queryStockCode = queryEntry.getValue();
			try {
				var articles = provider.search(query, properties.getDisplay());
				var originals = new ConcurrentHashMap<String, OriginalNewsArticle>();
				BoundedTasks.forEach(articles, 4, article -> {
					if (System.nanoTime() >= deadline) return;
					if (collectedIds.contains(article.providerArticleId())) return;
					var matches = stockMatcher.matchArticle(article.title(), "", mappings);
					Instant now = Instant.now(clock);
					if (matches.isEmpty() || (queryStockCode != null && !matches.containsKey(queryStockCode))
						|| article.publishedAt().isBefore(now.minus(properties.getMaxArticleAge()))
						|| article.publishedAt().isAfter(now.plus(Duration.ofMinutes(15)))) return;
					try {
						originalArticleGateway.fetch(article.originalUrl()).ifPresent(original -> originals.put(article.originalUrl(), original));
					} catch (RuntimeException exception) {
						log.warn("Original article failed type={}", exception.getClass().getSimpleName());
					}
				});
				for (CollectedNewsArticle article : articles) {
					if (collectedIds.contains(article.providerArticleId())) continue;
					if (store(article, queryStockCode, mappings, duplicateIndex, originals.get(article.originalUrl()))) {
						collectedIds.add(article.providerArticleId());
					}
				}
				if (queryStockCode != null && System.nanoTime() < deadline) {
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

	private boolean store(
		CollectedNewsArticle article,
		String queryStockCode,
		List<NewsStockMapping> mappings,
		NewsDuplicateIndex duplicateIndex,
		OriginalNewsArticle original
	) {
		Instant now = Instant.now(clock);
		if (article.publishedAt().isBefore(now.minus(properties.getMaxArticleAge()))
			|| article.publishedAt().isAfter(now.plus(Duration.ofMinutes(15)))) {
			return false;
		}
		Map<String, BigDecimal> preliminaryMatches = stockMatcher.matchArticle(
			article.title(),
			"",
			mappings
		);
		if (preliminaryMatches.isEmpty()
			|| (queryStockCode != null && !preliminaryMatches.containsKey(queryStockCode))) {
			return false;
		}
		if (original == null || original.body() == null || original.body().isBlank()) return false;
		Map<String, BigDecimal> stockMatches = stockMatcher.verifiedArticleMatches(article.title(), original.body(), mappings);
		if (stockMatches.isEmpty() || (queryStockCode != null && !stockMatches.containsKey(queryStockCode))) return false;
		String normalizedTitle = fingerprint.normalize(article.title());
		NewsFingerprint.Profile incoming = fingerprint.profile(article.title(), article.excerpt(), original == null ? "" : original.body());
		NewsDuplicateIndex.Match duplicate = duplicateIndex.findBest(
			incoming,
			article.publishedAt(),
			article.publisher(),
			stockMatches.keySet()
		);
		double bestScore = duplicate.score();
		UUID clusterId = duplicate.targetClusterId() != null
			? duplicate.targetClusterId()
			: UUID.randomUUID();
		if (duplicate.targetClusterId() != null) {
			// 유사 기사의 종목을 대표 기사에 전파하면 대표 원문에 없는 기업이 연결된다.
			return true;
		}
		String canonicalUrl = fingerprint.canonicalizeUrl(original.canonicalUrl());
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
			original.body(),
			article.originalUrl(),
			canonicalUrl,
			fingerprint.sha256(canonicalUrl),
			article.publisher(),
			original.thumbnailUrl(),
			original.sourcePolicy(),
			article.publishedAt(),
			now,
			BigDecimal.valueOf(bestScore),
			stockMatches
		);
		if (repository.saveCollected(draft)) {
			duplicateIndex.add(clusterId, incoming, article.publishedAt(), article.publisher(), stockMatches.keySet());
		}
		return true;
	}

	private void pause(Duration duration) {
		if (!duration.isNegative() && !duration.isZero()) {
			LockSupport.parkNanos(duration.toNanos());
		}
	}
}
