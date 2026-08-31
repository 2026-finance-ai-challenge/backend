package com.kmarket.navigator.backend.translation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.NewsStockMatcher;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class NewsNarrativePipelineWorker {

	private static final Logger log = LoggerFactory.getLogger(NewsNarrativePipelineWorker.class);
	private static final int BATCH_SIZE = 10;
	private final NewsRepository newsRepository;
	private final NewsStockMatcher stockMatcher;
	private final OnDemandTranslationService translationService;

	public NewsNarrativePipelineWorker(
		NewsRepository newsRepository,
		NewsStockMatcher stockMatcher,
		OnDemandTranslationService translationService
	) {
		this.newsRepository = newsRepository;
		this.stockMatcher = stockMatcher;
		this.translationService = translationService;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.news.narrative-queue-interval:5s}",
		initialDelayString = "${kmarket.news.narrative-queue-initial-delay:30s}"
	)
	@SchedulerLock(name = "news-narrative-queue", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1S")
	public void process() {
		var mappings = newsRepository.findStockMappings();
		for (var articleId : newsRepository.findNarrativeBackfillCandidates(BATCH_SIZE)) {
			try {
				var article = newsRepository.findById(articleId).orElse(null);
				if (article == null || stockMatcher.matchArticle(
					article.originalTitle(), article.originalBody(), mappings
				).isEmpty()) {
					continue;
				}
				translationService.ensureNewsRequested(articleId);
			}
			catch (RuntimeException exception) {
				log.warn(
					"News narrative queue failed articleId={} type={}",
					articleId,
					exception.getClass().getSimpleName()
				);
			}
		}
	}
}
