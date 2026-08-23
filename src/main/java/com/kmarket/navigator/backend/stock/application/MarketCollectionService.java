package com.kmarket.navigator.backend.stock.application;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.infrastructure.kis.KisMarketProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class MarketCollectionService {

	private static final Logger log = LoggerFactory.getLogger(MarketCollectionService.class);
	private static final List<String> INDEX_CODES = List.of("0001", "1001", "2001");
	private static final int MAX_CONSECUTIVE_FAILURES = 5;
	private final MarketDataGateway gateway;
	private final MarketSnapshotRepository repository;
	private final KisMarketProperties properties;

	public MarketCollectionService(
		MarketDataGateway gateway,
		MarketSnapshotRepository repository,
		KisMarketProperties properties
	) {
		this.gateway = gateway;
		this.repository = repository;
		this.properties = properties;
	}

	@Scheduled(
		fixedDelayString = "${kmarket.market.collection-interval:60s}",
		initialDelayString = "${kmarket.market.collection-initial-delay:15s}"
	)
	@SchedulerLock(
		name = "market-snapshot-collection",
		lockAtMostFor = "PT10M",
		lockAtLeastFor = "PT1S"
	)
	public void collect() {
		if (!gateway.configured()) {
			return;
		}
		int consecutiveFailures = 0;
		for (String stockCode : repository.findSupportedStockCodes()) {
			try {
				gateway.fetchQuote(stockCode)
					.ifPresent(quote -> repository.saveQuote(stockCode, quote));
				consecutiveFailures = 0;
			} catch (RuntimeException exception) {
				consecutiveFailures++;
				log.warn(
					"KIS quote collection failed for stockCode={} type={}",
					stockCode,
					exception.getClass().getSimpleName()
				);
				if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
					break;
				}
			}
			pause(properties.getCollectionDelay());
		}
		for (String indexCode : INDEX_CODES) {
			try {
				gateway.fetchIndex(indexCode).ifPresent(repository::saveIndex);
			} catch (RuntimeException exception) {
				log.warn(
					"KIS index collection failed for indexCode={} type={}",
					indexCode,
					exception.getClass().getSimpleName()
				);
			}
			pause(properties.getCollectionDelay());
		}
	}

	private void pause(Duration duration) {
		if (!duration.isNegative() && !duration.isZero()) {
			LockSupport.parkNanos(duration.toNanos());
		}
	}
}
