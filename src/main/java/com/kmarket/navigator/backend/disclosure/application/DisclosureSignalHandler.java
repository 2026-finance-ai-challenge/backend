package com.kmarket.navigator.backend.disclosure.application;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.news.application.port.NewsAiGateway;

@Service
public class DisclosureSignalHandler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureSignalHandler.class);
	private static final int MAX_ATTEMPTS = 5;
	private final DisclosureRepository repository;
	private final NewsAiGateway aiGateway;
	private final String workerId = UUID.randomUUID().toString();

	public DisclosureSignalHandler(DisclosureRepository repository, NewsAiGateway aiGateway) {
		this.repository = repository;
		this.aiGateway = aiGateway;
	}

	public boolean processNext() {
		var claimed = repository.claimSignalJob(workerId);
		if (claimed.isEmpty()) {
			return false;
		}
		var job = claimed.get();
		try {
			var analysis = aiGateway.analyze(
				job.title(), job.paragraphs(), job.candidateCompanies(), "DISCLOSURE"
			);
			repository.completeSignalJob(job.receiptNumber(), analysis);
		} catch (RuntimeException exception) {
			Duration delay = job.attempts() >= MAX_ATTEMPTS
				? Duration.ofHours(6)
				: Duration.ofMinutes((long) Math.pow(2, Math.max(0, job.attempts() - 1)));
			repository.retrySignalJob(job.receiptNumber(), exception.getClass().getSimpleName(), delay);
			log.warn("공시 분류 실패: receiptNumber={}, errorType={}",
				job.receiptNumber(), exception.getClass().getSimpleName());
		}
		return true;
	}
}
