package com.kmarket.navigator.backend.disclosure.infrastructure.scheduling;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import com.kmarket.navigator.backend.global.concurrent.BoundedTasks;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.DisclosureCollectionHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureDocumentHandler;
import com.kmarket.navigator.backend.disclosure.application.DisclosureSignalHandler;

@Component
@Profile("!test & !backfill & !title-backfill")
class DisclosureScheduler {

	private static final Logger log = LoggerFactory.getLogger(DisclosureScheduler.class);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final DisclosureCollectionHandler collectionHandler;
	private final DisclosureDocumentHandler documentHandler;
	private final DisclosureSignalHandler signalHandler;
	private final Clock clock;

	DisclosureScheduler(
		DisclosureCollectionHandler collectionHandler,
		DisclosureDocumentHandler documentHandler,
		DisclosureSignalHandler signalHandler,
		Clock clock
	) {
		this.collectionHandler = collectionHandler;
		this.documentHandler = documentHandler;
		this.signalHandler = signalHandler;
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
	@SchedulerLock(name = "processDisclosureDocuments", lockAtMostFor = "PT15M")
	void processDisclosureDocuments() {
		BoundedTasks.forEach(List.of(0, 1), 2, ignored -> documentHandler.processNext());
	}

	@Scheduled(fixedDelay = 2_000, initialDelay = 15_000)
	@SchedulerLock(name = "processDisclosureSignals", lockAtMostFor = "PT3M")
	void processDisclosureSignals() {
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
		BoundedTasks.forEach(List.of(0, 1), 2, ignored -> {
			for (int count = 0; count < 20 && System.nanoTime() < deadline && signalHandler.processNext(); count++) {
				// 과거 큐를 연속 처리하되 매 claim마다 최신 공시 우선순위를 다시 평가한다.
			}
		});
	}
}
