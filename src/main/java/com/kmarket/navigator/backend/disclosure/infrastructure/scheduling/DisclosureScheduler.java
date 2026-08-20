package com.kmarket.navigator.backend.disclosure.infrastructure.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureCollectionHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureDocumentHandler;

@Component
@Profile("!test & !backfill")
class DisclosureScheduler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureScheduler.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final DisclosureCollectionHandler collectionHandler;
	private final DisclosureDocumentHandler documentHandler;
	private final Clock clock;

	DisclosureScheduler(
		DisclosureCollectionHandler collectionHandler,
		DisclosureDocumentHandler documentHandler,
		Clock clock
	) {
		this.collectionHandler = collectionHandler;
		this.documentHandler = documentHandler;
		this.clock = clock;
	}

	@Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "collectCurrentDisclosures", lockAtMostFor = "PT4M")
	void collectCurrentDisclosures() {
		try {
			int saved = collectionHandler.collect(LocalDate.now(clock.withZone(SEOUL)));
			log.info("신규 공시 수집 완료: count={}", saved);
		}
		catch (RuntimeException exception) {
			log.error("공시 목록 수집 실패: errorType={}", exception.getClass().getName());
		}
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
	@SchedulerLock(name = "synchronizeCorporations", lockAtMostFor = "PT30M")
	void synchronizeCorporations() {
		try {
			collectionHandler.synchronizeCorporations();
			log.info("상장기업 동기화 완료");
		}
		catch (RuntimeException exception) {
			log.error("상장기업 동기화 실패: errorType={}", exception.getClass().getName());
		}
	}

	@Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
	@SchedulerLock(name = "processDisclosureDocuments", lockAtMostFor = "PT1M")
	void processDisclosureDocuments() {
		for (int index = 0; index < 5; index++) {
			if (!documentHandler.processNext()) {
				return;
			}
		}
	}
}
