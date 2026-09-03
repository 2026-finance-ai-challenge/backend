package com.kmarket.navigator.backend.stock.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.locks.LockSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.stock.application.port.ExchangeRateGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketDataGateway;
import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.infrastructure.kis.KisMarketProperties;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

@Service
public class MarketReferenceCollectionService {

	private static final Logger log = LoggerFactory.getLogger(MarketReferenceCollectionService.class);
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private final MarketDataGateway marketGateway;
	private final ExchangeRateGateway exchangeRateGateway;
	private final MarketSnapshotRepository repository;
	private final KisMarketProperties kisProperties;

	public MarketReferenceCollectionService(
		MarketDataGateway marketGateway,
		ExchangeRateGateway exchangeRateGateway,
		MarketSnapshotRepository repository,
		KisMarketProperties kisProperties
	) {
		this.marketGateway = marketGateway;
		this.exchangeRateGateway = exchangeRateGateway;
		this.repository = repository;
		this.kisProperties = kisProperties;
	}

	@Scheduled(fixedDelayString = "${kmarket.market.reference-interval:10m}", initialDelayString = "20s")
	@SchedulerLock(name = "market-exchange-rate-collection", lockAtMostFor = "PT9M")
	public void collectExchangeRate() {
		try {
			exchangeRateGateway.fetchUsdKrw().ifPresent(repository::saveExchangeRate);
		} catch (RuntimeException exception) {
			log.warn("Frankfurter exchange-rate collection failed type={}",
				exception.getClass().getSimpleName());
		}
	}

	@Scheduled(fixedDelayString = "${kmarket.market.foreign-flow-interval:1m}", initialDelayString = "25s")
	@SchedulerLock(name = "market-foreign-flow-collection", lockAtMostFor = "PT50S")
	public void collectForeignNetFlow() {
		if (!marketGateway.configured()) {
			return;
		}
		try {
			repository.saveForeignNetFlows(
				marketGateway.fetchForeignNetFlows(com.kmarket.navigator.backend.stock.domain.MarketQuoteWindow.latestStartedDate(java.time.Instant.now()))
			);
		} catch (RuntimeException exception) {
			log.warn("KIS foreign net-flow collection failed type={}",
				exception.getClass().getSimpleName());
		}
	}

	@Scheduled(cron = "${kmarket.market.history-cron:0 20 18 * * MON-FRI}", zone = "Asia/Seoul")
	@Scheduled(initialDelayString = "45s", fixedDelayString = "365d")
	@SchedulerLock(name = "market-history-collection", lockAtMostFor = "PT30M")
	public void collectHistory() {
		if (!marketGateway.configured()) {
			return;
		}
		LocalDate to = LocalDate.now(KOREA_ZONE);
		LocalDate from = to.minusYears(1);
		for (String stockCode : repository.findSupportedStockCodes()) {
			try {
				repository.saveDailyPrices(
					stockCode, marketGateway.fetchDailyPrices(stockCode, from, to)
				);
			} catch (RuntimeException exception) {
				log.warn("KIS daily-price collection failed stockCode={} type={}",
					stockCode, exception.getClass().getSimpleName());
			}
			pause(kisProperties.getCollectionDelay());
		}
	}

	private void pause(Duration duration) {
		if (duration != null && !duration.isNegative() && !duration.isZero()) {
			LockSupport.parkNanos(duration.toNanos());
		}
	}
}
