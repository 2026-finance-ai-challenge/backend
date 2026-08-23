package com.kmarket.navigator.backend.stock.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.stock.application.port.ForeignOwnershipGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.ForeignLimitCollectionTarget;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class ForeignOwnershipCollectionService {

	private static final Logger log = LoggerFactory.getLogger(ForeignOwnershipCollectionService.class);
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private static final int HISTORY_DAYS = 45;
	private final ForeignOwnershipGateway gateway;
	private final MarketSnapshotRepository repository;
	private final Clock clock;

	@Autowired
	public ForeignOwnershipCollectionService(
		ForeignOwnershipGateway gateway,
		MarketSnapshotRepository repository
	) {
		this(gateway, repository, Clock.system(KOREA_ZONE));
	}

	ForeignOwnershipCollectionService(
		ForeignOwnershipGateway gateway,
		MarketSnapshotRepository repository,
		Clock clock
	) {
		this.gateway = gateway;
		this.repository = repository;
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void collectAfterStartup() {
		collect();
	}

	@Scheduled(cron = "${kmarket.market.krx.collection-cron:0 30 18 * * MON-FRI}", zone = "Asia/Seoul")
	@SchedulerLock(
		name = "foreign-ownership-collection",
		lockAtMostFor = "PT10M",
		lockAtLeastFor = "PT1S"
	)
	public void collect() {
		if (!gateway.configured()) {
			return;
		}
		LocalDate to = LocalDate.now(clock);
		LocalDate from = to.minusDays(HISTORY_DAYS);
		for (ForeignLimitCollectionTarget target : repository.findForeignLimitTargets()) {
			try {
				gateway.fetchHistory(target, from, to)
					.forEach(snapshot -> repository.saveForeignOwnership(target.stockCode(), snapshot));
			} catch (RuntimeException exception) {
				log.warn(
					"KRX foreign ownership collection failed for stockCode={} type={}",
					target.stockCode(),
					exception.getClass().getSimpleName()
				);
			}
		}
	}
}
