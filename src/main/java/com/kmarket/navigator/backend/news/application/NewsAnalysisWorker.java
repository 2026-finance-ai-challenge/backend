package com.kmarket.navigator.backend.news.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;
import com.kmarket.navigator.backend.news.application.port.NewsRepository;
import com.kmarket.navigator.backend.news.domain.NewsAnalysisJob;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class NewsAnalysisWorker {

	private static final Logger log = LoggerFactory.getLogger(NewsAnalysisWorker.class);
	private static final int BATCH_SIZE = 10;
	private final NewsRepository repository;
	private final NewsAiGateway aiGateway;
	private final Clock clock;

	@Autowired
	public NewsAnalysisWorker(NewsRepository repository, NewsAiGateway aiGateway) {
		this(repository, aiGateway, Clock.systemUTC());
	}

	NewsAnalysisWorker(NewsRepository repository, NewsAiGateway aiGateway, Clock clock) {
		this.repository = repository;
		this.aiGateway = aiGateway;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${kmarket.news.analysis-interval:5s}")
	@SchedulerLock(name = "news-analysis", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1S")
	public void process() {
		Instant now = Instant.now(clock);
		for (NewsAnalysisJob job : repository.claimAnalysisJobs(BATCH_SIZE, now)) {
			try {
				var analysis = aiGateway.analyze(
					job.title(),
					job.paragraphs(),
					job.candidateCompanies()
				);
				repository.completeAnalysis(job.articleId(), analysis, Instant.now(clock));
			} catch (RuntimeException exception) {
				Duration delay = Duration.ofSeconds(Math.min(3_600, 30L << Math.min(job.attempts(), 6)));
				repository.failAnalysis(
					job.articleId(),
					job.attempts(),
					exception.getClass().getSimpleName(),
					Instant.now(clock),
					delay
				);
				log.warn(
					"News analysis failed for articleId={} type={}",
					job.articleId(),
					exception.getClass().getSimpleName()
				);
			}
		}
	}
}
