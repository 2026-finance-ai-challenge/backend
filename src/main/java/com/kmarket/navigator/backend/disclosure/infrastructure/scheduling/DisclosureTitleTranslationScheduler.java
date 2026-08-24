package com.kmarket.navigator.backend.disclosure.infrastructure.scheduling;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureTitleTranslationWorker;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Component
@Profile("!test & !backfill & !title-backfill")
class DisclosureTitleTranslationScheduler {

	private static final int BATCH_SIZE = 100;
	private final DisclosureTitleTranslationWorker worker;

	DisclosureTitleTranslationScheduler(DisclosureTitleTranslationWorker worker) {
		this.worker = worker;
	}

	@Scheduled(fixedDelayString = "${kmarket.disclosure.title-translation-interval:5s}")
	@SchedulerLock(name = "disclosure-title-translation", lockAtMostFor = "PT4M")
	void process() {
		worker.processBatch(BATCH_SIZE);
	}
}
